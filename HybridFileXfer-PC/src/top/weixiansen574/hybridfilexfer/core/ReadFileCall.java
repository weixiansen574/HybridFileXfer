package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public abstract class ReadFileCall implements Callable<Void> {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    private int fileIndex = -1;

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
    }

    @Override
    public Void call() throws Exception {
        try {
            for (RemoteFile file : files) {
                if (!fileExists(file.getPath())) {
                    continue;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(END_POINT);
            }
        } catch (Exception e) {
            //当发生读取错误时
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(READ_ERROR);
            }
            throw e;
        }
        return null;
    }

    private void listFilesAndRead(RemoteFile folder) throws Exception {
        List<RemoteFile> files = listFiles(folder.getPath());
        if (files != null) {
            for (RemoteFile file : files) {
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file); // 递归遍历子文件夹
                }
            }
        }
    }

    /**
     * 只生成“块描述符”，不读取任何文件数据。
     * <p>
     * 真正的数据由 {@link SendFileCall} 通过 {@code FileChannel.transferTo} 零拷贝直接
     * 从 page cache 送入 socket，因此这里既不占用缓冲池，也不产生用户态拷贝。
     * <p>
     * 顺带修掉了原实现里的两个问题：
     * <ul>
     *   <li>{@code channel.read()} 返回值未检查导致的死循环；</li>
     *   <li>每个文件都要 open/close 一次只为拿 size。</li>
     * </ul>
     */
    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        String targetPath = localDir.generateTransferPath(file.getPath(), remoteDir);
        if (file.isDirectory()) {
            deque.add(new FileBlock(false, fileIndex, targetPath, file.lastModified(), 0, 0, null));
            return;
        }
        // 用实际打开后的 size，而不是 RemoteFile.getSize() 的列表快照：
        // 若文件在列出列表之后被改动，快照会偏小，导致静默截断（接收端得到一个残缺但“看起来正常”的文件）。
        long length;
        SourceHandle probe = openSource(file.getPath());
        try {
            length = probe.channel().size();
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
            }
        }
        long lastModified = file.lastModified();
        if (length <= 0) {
            // 空文件也要发一个 length=0 的块，否则接收端不会创建该文件
            deque.add(new FileBlock(fileIndex, targetPath, file.getPath(), lastModified, 0, 0, 0));
            return;
        }
        int i = 0;
        long remaining = length;
        while (remaining > 0) {
            int blkSize = (int) Math.min(remaining, FileBlock.BLOCK_SIZE);
            deque.add(new FileBlock(fileIndex, targetPath, file.getPath(), lastModified, length, i, blkSize));
            remaining -= blkSize;
            i++;
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        buffers.add(buffer);
    }

    public FileBlock takeBlock() throws InterruptedException {
        return deque.take();
    }

    //当对方写入时发生错误时
    public void shutdownByWriteError() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount; i++) {
            deque.addFirst(WRITE_ERROR);
        }
    }

    //当其中任意一条通道断开时
    public void shutdownByConnectionBreak() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount - 1; i++) {
            deque.addFirst(INTERRUPT);
        }
    }

    private void recycleAllBuffer() {
        for (FileBlock fileBlock : deque) {
            if (fileBlock.data != null) {
                recycleBuffer(fileBlock.data);
            }
        }
    }

    /**
     * 打开一个只读源句柄供 sendfile 使用。
     * 句柄生命周期由 {@link SendFileCall} 管理（同一文件复用，换文件即关闭）。
     */
    public abstract SourceHandle openSource(String path) throws Exception;

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

}
