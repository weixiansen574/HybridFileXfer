package top.weixiansen574.hybridfilexfer.core.bean;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class TransferJob {
    public File localDir;
    public String remoteDir;

    public Queue<File> fileQueue;

    public TransferJob(File localDir, String remoteDir, List<File> files) {
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.fileQueue = new ArrayDeque<>();
        fileQueue.addAll(files);
    }


}
