package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferJob;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;

public class SendFilesCall extends TransferCall {

    private final TransferChannel tChannel;
    private final JobPool jobPool;

    public SendFilesCall(TransferChannel tChannel, TransferEventDeque eventDeque,JobPool jobPool) {
        super(tChannel,eventDeque);
        this.tChannel = tChannel;
        this.jobPool = jobPool;
    }

    @Override
    public Void call() throws Exception {
        try {
            while (true) {
                if (jobPool.isInterrupted()){
                    eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_INTERRUPTED,tChannel.iName,null));
                    dos.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                    return null;
                }
                FileTransferJob job = jobPool.getNextJob();
                if (job == null) {
                    dos.writeShort(TransferIdentifiers.EOF);
                    eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_UPLOAD_OVER,tChannel.iName,null));
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
            eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_ERROR,tChannel.iName,e.toString()));
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
            eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_UPLOAD, tChannel.iName, desc));
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
                    // 每次最多传输64KB
                    long bytesToTransfer = Math.min(CHUNK_SIZE, size - position);
                    long transferred = fileChannel.transferTo(position, bytesToTransfer, socketChannel);
                    if (transferred <= 0) {
                        break;
                    }
                    position += transferred;
                    tChannel.addUploadedBytes(transferred);
                }
            }
        } else if (file.isDirectory()) {
            System.out.println("{" + Thread.currentThread().getName() + "}: " + file + " ==> " + remotePath);
            eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_UPLOAD, tChannel.iName, file.getPath()));
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
        eventDeque.addEvent(new TransferEvent(TransferEvent.TYPE_UPLOAD, tChannel.iName, desc));
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
                //如果CHUNK_SIZE越大，对CPU的压力越小，但是当前速度的显示越不准
                long bytesToTransfer = Math.min(CHUNK_SIZE, remaining);
                long transferred = fileChannel.transferTo(position, bytesToTransfer, socketChannel);
                if (transferred <= 0) {
                    break;
                }
                position += transferred;
                remaining -= transferred;
                tChannel.addUploadedBytes(transferred);
            }
        }
    }
}
