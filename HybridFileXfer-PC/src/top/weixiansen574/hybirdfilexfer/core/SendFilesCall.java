package top.weixiansen574.hybirdfilexfer.core;

import top.weixiansen574.hybirdfilexfer.core.bean.FileTransferJob;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Locale;

public class SendFilesCall extends TransferCall {
    private final JobPool jobPool;

    public SendFilesCall(TransferChannel tChannel,JobPool jobPool) {
        super(tChannel);
        this.jobPool = jobPool;
    }

    @Override
    public Void call() throws Exception {
        try {
            while (true) {
                if (jobPool.isInterrupted()){
                    dos.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                    System.out.println(Thread.currentThread().getName() + ": 因其他通道出现问题，传输已中断");
                    return null;
                }
                FileTransferJob job = jobPool.getNextJob();
                if (job == null) {
                    dos.writeShort(TransferIdentifiers.EOF);
                    System.out.println(Thread.currentThread().getName() + " 发送完成");
                    break;
                }
                File nextFile = job.targetFile;
                String remotePath = job.toRemotePath();
                if (!job.isSlice) {
                    sendFileOrDir(nextFile,remotePath);
                } else {
                    sendFileSlice(job,nextFile,remotePath);
                }
            }
        } catch (Exception e){
            jobPool.setInterrupted(true);
            throw e;
        }
        return null;
    }

    private void sendFileOrDir(File file, String remotePath) throws IOException {
        if (file.isFile()) {
            String desc = String.format(Locale.getDefault(), "[%.2fMB] %s",
                    ((float) file.length()) / 1024 / 1024,
                    file.getCanonicalPath());

            System.out.println("{" + Thread.currentThread().getName() + "}: " + desc + " ==> " + remotePath);
            dos.writeShort(TransferIdentifiers.FILE); // 文件标识
            dos.writeUTF(remotePath); // 远程文件路径
            dos.writeLong(file.lastModified()); // 修改日期
            dos.writeLong(file.length()); // 内容长度

            // 使用FileChannel进行零拷贝传输
            try (FileInputStream fileInputStream = new FileInputStream(file);
                 FileChannel fileChannel = fileInputStream.getChannel()) {

                long size = file.length();
                long position = 0;
                while (position < size) {
                    //long bytesToTransfer = Math.min(CHUNK_SIZE, size - position);
                    long transferred = fileChannel.transferTo(position, size - position, socketChannel);
                    if (transferred <= 0) {
                        break;
                    }
                    position += transferred;
                }
            }
        } else if (file.isDirectory()) {
            System.out.println("{" + Thread.currentThread().getName() + "}: " + file + " ==> " + remotePath);
            dos.writeShort(TransferIdentifiers.FOLDER); // 文件夹标识
            dos.writeUTF(remotePath); // 远程文件路径
            dos.writeLong(file.lastModified()); // 修改日期
        }
    }

    private void sendFileSlice(FileTransferJob job, File file, String remotePath) throws IOException {
        String desc = String.format(Locale.getDefault(), "[%dMB-%dMB/%dMB] %s",
                job.startRange / 1024 / 1024,
                job.endRange / 1024 / 1024,
                job.getTotalSize() / 1024 / 1024,
                file.getCanonicalPath());

        System.out.println("{" + Thread.currentThread().getName() + "}" + desc + " ==> " + remotePath);
        dos.writeShort(TransferIdentifiers.FILE_SLICE); // 文件切片标识
        dos.writeUTF(remotePath); // 远程文件路径
        dos.writeLong(file.lastModified()); // 修改日期
        dos.writeLong(job.getTotalSize()); // 文件总长度
        dos.writeLong(job.startRange); // 起始点
        dos.writeLong(job.endRange); // 结束点

        // 使用FileChannel配合RandomAccessFile进行切片传输
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel fileChannel = raf.getChannel()) {
            long position = job.startRange;
            long remaining = job.endRange - job.startRange;

            while (remaining > 0) {
                long transferred = fileChannel.transferTo(position, remaining, socketChannel);
                if (transferred <= 0) {
                    break;
                }
                position += transferred;
                remaining -= transferred;
            }
        }
    }
}
