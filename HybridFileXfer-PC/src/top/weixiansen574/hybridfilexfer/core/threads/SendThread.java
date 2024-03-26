package top.weixiansen574.hybridfilexfer.core.threads;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;
import top.weixiansen574.hybridfilexfer.core.bean.FileTransferJob;
import top.weixiansen574.hybridfilexfer.core.JobPublisher;
import top.weixiansen574.hybridfilexfer.core.TransferIdentifiers;

public class SendThread extends TransferThread {

    JobPublisher jobPublisher;
    DataOutputStream dos;

    public SendThread(BlockingDeque<FileTransferEvent> events, int device, JobPublisher jobPublisher, OutputStream os) {
        super(events, device);
        this.jobPublisher = jobPublisher;
        this.dos = new DataOutputStream(os);
    }

    @Override
    public void run() {
        try {
            while (true) {
                FileTransferJob job;
                try {
                    job = jobPublisher.getNextJob();
                } catch (InterruptedException e) {
                    //发送终止标识，对方收到后主动关闭连接
                    dos.writeShort(TransferIdentifiers.END_POINT);
                    System.out.println(getName() + " 发送线程已终止");
                    break;
                }
                File nextFile = job.targetFile;
                String remotePath = job.toRemotePath();
                if (!job.isSlice) {
                    if (nextFile.isFile()) {
                        String desc = String.format(Locale.getDefault(), "[%.2fMB] %s",
                                ((float) nextFile.length()) / 1024 / 1024,
                                nextFile.getCanonicalPath());

                        System.out.println("{" + currentThread().getName() + "}: " + desc + " ==> " + remotePath);
                        addEvent(FileTransferEvent.STATE_UPLOAD, desc);
                        dos.writeShort(TransferIdentifiers.FILE);//文件标识
                        dos.writeUTF(remotePath);//远程文件路径
                        dos.writeLong(nextFile.lastModified());//修改日期
                        dos.writeLong(nextFile.length());//内容长度
                        byte[] buffer = new byte[4096];
                        FileInputStream fileInputStream = new FileInputStream(nextFile);
                        int len;
                        while ((len = fileInputStream.read(buffer)) != -1) {
                            dos.write(buffer, 0, len);
                            transferredBytes += len;
                        }
                        fileInputStream.close();
                        addEvent(FileTransferEvent.STATE_OVER, desc);
                    } else if (nextFile.isDirectory()) {
                        System.out.println("{" + currentThread().getName() + "}: " + nextFile + " ==> " + remotePath);
                        addEvent(FileTransferEvent.STATE_UPLOAD, nextFile.getCanonicalPath());
                        dos.writeShort(TransferIdentifiers.FOLDER);//文件夹标识
                        dos.writeUTF(remotePath);//远程文件路径
                        dos.writeLong(nextFile.lastModified());//修改日期
                        addEvent(FileTransferEvent.STATE_OVER, nextFile.getCanonicalPath());
                    }
                } else {
                    String desc = String.format(Locale.getDefault(), "[%dMB-%dMB/%dMB] %s",
                            job.startRange / 1024 / 1024,
                            job.endRange / 1024 / 1024,
                            job.getTotalSize() / 1024 / 1024,
                            nextFile.getCanonicalPath());

                    System.out.println("{" + currentThread().getName() + "}" + desc + " ==> " + remotePath);
                    addEvent(FileTransferEvent.STATE_UPLOAD, desc);
                    dos.writeShort(TransferIdentifiers.FILE_SLICE);//文件切片标识
                    dos.writeUTF(remotePath);//远程文件路径
                    dos.writeLong(nextFile.lastModified());//修改日期
                    dos.writeLong(job.getTotalSize());//文件总长度
                    dos.writeLong(job.startRange);//起始点
                    dos.writeLong(job.endRange);//结束点
                    RandomAccessFile raf = new RandomAccessFile(nextFile, "r");
                    raf.seek(job.startRange);
                    int outputLength = (int) (job.endRange - job.startRange);
                    byte[] buffer = new byte[4096];
                    boolean canContinue = true;
                    while (canContinue) {
                        int len;
                        if (outputLength >= 4096) {
                            len = 4096;
                            outputLength -= 4096;
                        } else {
                            len = outputLength;
                            canContinue = false;
                        }
                        int read = raf.read(buffer, 0, len);
                        dos.write(buffer, 0, read);
                        transferredBytes += read;
                    }
                    raf.close();
                    addEvent(FileTransferEvent.STATE_OVER, desc);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println(getName() + " 发送线程已终止，因为发生了异常");
            if (onExceptionListener != null){
                onExceptionListener.onException(e);
            }
        }
    }

    public void shutdown() {
        interrupt();
    }


}
