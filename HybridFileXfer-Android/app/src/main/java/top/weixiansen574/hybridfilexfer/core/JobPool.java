package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferJob;

public class JobPool {
    public static final int sliceSize = 16 * 1024 * 1024;
    public File localDir;
    public String remoteDir;
    private FileTransferJob currentFileSliceJob;
    private final Queue<File> fileQueue;
    private boolean interrupted = false;

    public JobPool(File localDir, String remoteDir, List<File> files) {
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.fileQueue = new ArrayDeque<>(files);
    }

    public synchronized FileTransferJob getNextJob() {
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
            return null;
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
                currentFileSliceJob = new FileTransferJob(localDir, remoteDir, file, 0, sliceSize);
                return currentFileSliceJob;
            }
        }
        //如果不是分片则返回正常的文件传输任务(文件/文件夹)
        return new FileTransferJob(localDir, remoteDir, file);
    }

    public synchronized boolean isInterrupted() {
        return interrupted;
    }

    public synchronized void setInterrupted(boolean interrupted) {
        this.interrupted = interrupted;
    }
}
