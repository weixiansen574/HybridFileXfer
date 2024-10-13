package top.weixiansen574.hybirdfilexfer.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.Locale;

public class ReceiveFilesCall extends TransferCall {

    public ReceiveFilesCall(TransferChannel tChannel) {
        super(tChannel);
    }

    @Override
    public Void call() throws Exception {
        short identifier;
        while ((identifier = dis.readShort()) != TransferIdentifiers.EOF) {
            if (identifier == TransferIdentifiers.FILE) {
                receiveFile();
            } else if (identifier == TransferIdentifiers.FOLDER) {
                receiveDir();
            } else if (identifier == TransferIdentifiers.FILE_SLICE) {
                receiveFileSlice();
            } else if (identifier == TransferIdentifiers.END_OF_INTERRUPTED){
                System.out.println(Thread.currentThread().getName() + " 已中断，如果本程序（电脑端）没有正常结束，请手动关闭！");
                return null;
            }
        }
        System.out.println(Thread.currentThread().getName() + " 接收完毕");
        return null;
    }


    private void receiveDir() throws IOException {
        String filePath = dis.readUTF(); // 文件路径
        long lastModified = dis.readLong(); // 修改时间

        // 创建目录
        File file = new File(filePath);
        if (!file.exists()) {
            file.mkdirs();
        }
        file.setLastModified(lastModified);
        System.out.println(filePath);
    }

    private void receiveFile() throws IOException {
        String filePath = dis.readUTF(); // 文件路径
        long lastModified = dis.readLong(); // 修改时间
        long remainingLength = dis.readLong(); // 文件大小

        String desc = String.format(Locale.getDefault(), "[%.2fMB] %s",
                ((float) remainingLength) / 1024 / 1024,
                filePath);

        File file = new File(filePath);
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }

        try (RandomAccessFile raf = newRAF(file, remainingLength); // new RandomAccessFile(file, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            transferData(fileChannel, remainingLength, 0);

            // 设置文件的最后修改日期
            file.setLastModified(lastModified);
            System.out.println("{" + Thread.currentThread().getName() + "} " + desc);
        }
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

        File file = new File(filePath);
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }

        try (RandomAccessFile raf = newRAF(file, totalSize); // new RandomAccessFile(file, "rw");
             FileChannel fileChannel = raf.getChannel()) {

            // 设置起始点
            raf.seek(startRange);
            long remainingLength = endRange - startRange;

            transferData(fileChannel, remainingLength, startRange);

            // 设置文件的最后修改日期
            file.setLastModified(lastModified);
            System.out.println("{" + Thread.currentThread().getName() + "} " + desc);
        }
    }

    private void transferData(FileChannel fileChannel, long remainingLength, long position) throws IOException {
        while (remainingLength > 0) {
            long transferred = fileChannel.transferFrom(socketChannel, position, remainingLength);
            if (transferred <= 0) break;  // 传输完成或发生错误
            position += transferred;
            remainingLength -= transferred;
        }
    }
}
