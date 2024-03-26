package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferJob;
import top.weixiansen574.hybridfilexfer.core.bean.TransferJob;

public class JobPublisher {
    public static final int sliceSize = 16 * 1024 * 1024;
    private FileTransferJob currentFileSliceJob;
    private TransferJob currentTransferJob;
    private final BlockingDeque<TransferJob> transferJobQueue;
    private Queue<File> fileQueue;

    public JobPublisher() {
        this.transferJobQueue = new LinkedBlockingDeque<>();
        this.fileQueue = new ArrayDeque<>();
    }

    public synchronized FileTransferJob getNextJob() throws InterruptedException {
        if (currentFileSliceJob != null) {
            long startSeek = currentFileSliceJob.startRange + sliceSize;
            long endSeek = startSeek + sliceSize;
            if (endSeek <= currentFileSliceJob.getTotalSize()) {
                currentFileSliceJob = new FileTransferJob(currentFileSliceJob,startSeek,endSeek);
                return currentFileSliceJob;
            } else if (startSeek <= currentFileSliceJob.getTotalSize()) {
                currentFileSliceJob = new FileTransferJob(currentFileSliceJob,startSeek,currentFileSliceJob.getTotalSize());
                return currentFileSliceJob;
            } else {
                currentFileSliceJob = null;
            }
        }

        if (fileQueue.isEmpty()) {
            //若没任务了就会一直等待，直到另一个线程添加任务
            currentTransferJob = Objects.requireNonNull(transferJobQueue.take());
            fileQueue = currentTransferJob.fileQueue;
        }

        File file = fileQueue.poll();
        assert file != null;
        if (file.isDirectory()) {
            // 如果当前文件是文件夹，将其子文件和子文件夹添加到队列
            File[] files = file.listFiles();
            if (files != null) {
                fileQueue.addAll(Arrays.asList(files));
            }
        } else if (file.isFile()) {
            if (file.length() >= sliceSize * 4) {//如果是文件且大于16*4=64MB，则返回切片任务，然后设置当前切片
                currentFileSliceJob = new FileTransferJob(
                        currentTransferJob.localDir,
                        currentTransferJob.remoteDir,
                        file, 0, sliceSize);
                return currentFileSliceJob;
            }
        }
        //如果不是分片则返回正常的文件传输任务(文件/文件夹)
        return new FileTransferJob(currentTransferJob.localDir, currentTransferJob.remoteDir, file);
    }



    public void addJob(TransferJob transferJob) {
        transferJobQueue.add(transferJob);
    }


}
