package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class SendFileCall implements Callable<Void> {
    private final ReadFileCall readFileCall;
    private final DataByteChannel channel;
    /**
     * 必须是原始 SocketChannel。
     * {@code FileChannel.transferTo} 只有在目标是 SocketChannel 时才会走 sendfile/TransmitFile
     * 零拷贝路径；若传入包装过的 DataByteChannel，JDK 会退化成普通读写拷贝，收益归零。
     */
    private final SocketChannel socket;
    private final TransferConnection connection;
    private final TransferFileCallback callback;

    /** 当前复用的源文件句柄，换文件时替换 */
    private SourceHandle source;
    private String sourcePath;

    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection, TransferFileCallback callback) {
        this.readFileCall = readFileCall;
        this.connection = connection;
        this.channel = connection.channel;
        this.socket = connection.socket;
        this.callback = callback;
        connection.resetTotalTrafficInfo();
    }

    @Override
    public Void call() throws Exception {
        FileBlock fileBlock = null;
        try {
            long startTime = System.currentTimeMillis();
            while (true) {
                fileBlock = readFileCall.takeBlock();
                //-1为特殊块
                if (fileBlock.fileIndex == -1) {
                    if (fileBlock == ReadFileCall.END_POINT) {
                        channel.writeShort(TransferIdentifiers.EOF);
                        callback.onChannelComplete(connection.iName,
                                connection.getTotalTraffic().uploadTraffic,
                                System.currentTimeMillis() - startTime);
                    } else if (fileBlock == ReadFileCall.INTERRUPT) {
                        channel.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_INTERRUPT, null);
                    } else if (fileBlock == ReadFileCall.READ_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_READ_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_READ_ERROR, null);
                    } else if (fileBlock == ReadFileCall.WRITE_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_WRITE_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_WRITE_ERROR, null);
                    }
                    break;
                }
                if (!fileBlock.isFile) {
                    // 目录：类型 + 序号 + 路径 + 修改时间，一次写出
                    channel.writeBlockHeader(TransferIdentifiers.FOLDER, fileBlock.fileIndex,
                            fileBlock.path, fileBlock.lastModified, false, 0, 0, 0);
                    continue;
                }
                // 块头一次写出：原来是 7 次独立 write，等于每块 7 次 syscall + 7 个小包
                channel.writeBlockHeader(TransferIdentifiers.FILE, fileBlock.fileIndex,
                        fileBlock.path, fileBlock.lastModified, true,
                        fileBlock.totalSize, fileBlock.index, fileBlock.getLength());

                callback.onFileUploading(connection.iName, fileBlock.path,
                        fileBlock.getStartPosition() + fileBlock.getLength(),
                        fileBlock.totalSize);

                transferBlock(fileBlock);
            }
        } catch (Exception e) {
            //若发生异常，通知其他传输通道，停止传输
            if (fileBlock != null && fileBlock.data != null) {
                //回收文件分块的ByteBuffer，否则导致这个Buffer免费了
                readFileCall.recycleBuffer(fileBlock.data);
            }
            readFileCall.shutdownByConnectionBreak();
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        } finally {
            closeSource();
        }
        return null;
    }

    /**
     * 零拷贝发送一个块：数据从 page cache 直接进 socket，不经过用户内存。
     * <p>
     * transferTo 允许返回小于请求的字节数（发送缓冲区满时），必须循环推进，
     * 否则会导致流错位——这是零拷贝改造最容易踩的坑。
     */
    private void transferBlock(FileBlock block) throws Exception {
        long remain = block.getLength();
        if (remain <= 0) {
            // 空文件：只发了头部，接收端据此创建一个 0 字节文件
            return;
        }
        // 必须用 sourcePath（本地真实路径）打开源文件；block.path 是交给对端的落盘路径，
        // 拿它 open 会得到一个指向对端文件系统的路径，必然失败。
        SourceHandle src = sourceFor(block.sourcePath);
        FileChannel fc = src.channel();
        long offset = block.fileOffset;
        while (remain > 0) {
            long written = fc.transferTo(offset, remain, socket);
            if (written <= 0) {
                throw new IOException("transferTo made no progress: n=" + written
                        + ", offset=" + offset + ", remain=" + remain
                        + ", source=" + block.sourcePath + ", target=" + block.path);
            }
            offset += written;
            remain -= written;
            connection.addUploadedBytes(written);
        }
    }

    private SourceHandle sourceFor(String path) throws Exception {
        if (path == null) {
            throw new IOException("block carries no sourcePath; refusing to open an unknown path");
        }
        if (source != null && path.equals(sourcePath)) {
            return source;
        }
        closeSource();
        source = readFileCall.openSource(path);
        sourcePath = path;
        return source;
    }

    private void closeSource() {
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
            source = null;
            sourcePath = null;
        }
    }
}
