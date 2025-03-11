package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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

    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        if (file.isDirectory()) {
            deque.add(new FileBlock(false,
                    fileIndex, localDir.generateTransferPath(file.getPath(), remoteDir),
                    file.lastModified(), 0, 0, null));
            return;
        }
        //RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        FileChannel channel = openFile(file.getPath());
        long length = channel.size();
        long lastModified = file.lastModified();
        long remaining = length;
        if (length == 0){
            ByteBuffer buffer = buffers.take();
            buffer.clear();
            buffer.limit(0);
            deque.add(new FileBlock(true,
                    fileIndex, localDir.generateTransferPath(file.getPath(), remoteDir),
                    lastModified, length, 0, buffer));
            closeFile();
            return;
        }
        int i = 0;
        while (remaining > 0){
            int blkSize = (int) Math.min(remaining,FileBlock.BLOCK_SIZE);
            ByteBuffer buffer = buffers.take();
            buffer.clear();
            buffer.limit(blkSize);
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
            deque.add(new FileBlock(true,
                    fileIndex, localDir.generateTransferPath(file.getPath(), remoteDir),
                    lastModified, length, i, buffer));
            remaining -= blkSize;
            i++;
        }
        closeFile();
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

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract FileChannel openFile(String path) throws Exception;

    protected abstract void closeFile() throws Exception;

}
