package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;

public class ReceiveFilesCall extends TransferCall{
    public ReceiveFilesCall(TransferChannel tChannel, TransferEventDeque eventDeque) {
        super(tChannel, eventDeque);
    }

    @Override
    public Void call() throws Exception {
        try {
            short identifier;
            while ((identifier = dis.readShort()) != TransferIdentifiers.EOF) {
                if (identifier == TransferIdentifiers.FILE) {
                    receiveFile();
                } else if (identifier == TransferIdentifiers.FOLDER) {
                    receiveDir();
                } else if (identifier == TransferIdentifiers.FILE_SLICE) {
                    receiveFileSlice();
                } else if (identifier == TransferIdentifiers.END_OF_INTERRUPTED){
                    System.out.println(Thread.currentThread().getName() + " 已中断");
                    eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_INTERRUPTED,tChannel.iName,null));
                    return null;
                }
            }
            eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_DOWNLOAD_OVER,tChannel.iName,null));
            System.out.println(Thread.currentThread().getName() + " 接收完毕");
        } catch (Exception e){
            eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_ERROR,tChannel.iName,e.toString()));
            throw e;
        }
        return null;
    }

    private void receiveFile() throws IOException {
        String filePath = dis.readUTF(); // 文件路径
        long lastModified = dis.readLong(); // 修改时间
        long size = dis.readLong(); // 文件大小

        String desc = String.format(Locale.getDefault(), "[%.2fMB] %s",
                ((float) size) / 1024 / 1024,
                filePath);
        eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_DOWNLOAD,tChannel.iName,desc));
        // 创建文件
        File file = new File(filePath);
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }

        // 使用 FileChannel 和 transferFrom 实现零拷贝
        try (RandomAccessFile raf = newRAF(file, size);//new RandomAccessFile(file, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            // 读取数据并将其直接传输到文件
            long position = 0;
            while (position < size) {
                long bytesToTransfer = Math.min(CHUNK_SIZE, size - position);
                long transferred = fileChannel.transferFrom(socketChannel, position, bytesToTransfer);
                if (transferred <= 0) break;
                position += transferred;
                tChannel.addDownloadedBytes(transferred);
            }

            // 设置文件的最后修改日期
            file.setLastModified(lastModified);
            System.out.println("{" + Thread.currentThread().getName() + "} " + desc);
        }
    }

    //@SuppressWarnings("all")
    private void receiveDir() throws IOException {
        String filePath = dis.readUTF(); // 文件路径
        long lastModified = dis.readLong(); // 修改时间
        eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_DOWNLOAD,tChannel.iName,filePath));
        // 创建目录
        File file = new File(filePath);
        if (!file.exists()) {
            file.mkdirs();
        }
        file.setLastModified(lastModified);
        System.out.println(filePath);
    }

    private void receiveFileSlice() throws IOException {
        String filePath = dis.readUTF(); // 文件路径
        long lastModified = dis.readLong(); // 修改时间
        long totalSize = dis.readLong(); // 总大小
        long startRange = dis.readLong(); // 起始点
        long endRange = dis.readLong(); // 结束点

        String desc = String.format(Locale.getDefault(), "[%dMB-%dMB/%dMB] %s",
                startRange / 1024 / 1024,
                endRange / 1024 / 1024,
                totalSize / 1024 / 1024,
                filePath);
        eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_DOWNLOAD,tChannel.iName,desc));

        File file = new File(filePath);
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }

        // 使用 FileChannel 和 transferFrom 传输切片
        try (RandomAccessFile raf = newRAF(file, totalSize);//new RandomAccessFile(file, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            // 设置起始点
            raf.seek(startRange);
            long position = startRange;
            long remaining = endRange - startRange;

            while (remaining > 0) {
                long bytesToTransfer = Math.min(CHUNK_SIZE, remaining);
                long transferred = fileChannel.transferFrom(socketChannel, position, bytesToTransfer);
                if (transferred <= 0) break;  // 传输完成或发生错误
                position += transferred;
                remaining -= transferred;
                tChannel.addDownloadedBytes(transferred);

            }

            // 设置文件的最后修改日期
            file.setLastModified(lastModified);
            System.out.println("{" + Thread.currentThread().getName() + "} " + desc);
        }
    }

    public static synchronized RandomAccessFile newRAF(File path, long totalSize) throws IOException {
        if (!path.exists()) {
            RandomAccessFile randomAccessFile = new RandomAccessFile(path,"rw");
            randomAccessFile.setLength(totalSize);
            return randomAccessFile;
        } else {
            return new RandomAccessFile(path,"rw");
        }
    }


}
