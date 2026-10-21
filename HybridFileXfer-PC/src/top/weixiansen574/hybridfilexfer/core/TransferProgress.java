package top.weixiansen574.hybridfilexfer.core;

/** 传输实时进度快照，按接收端已确认落盘的字节数统计。 */
public final class TransferProgress {
    public long transferredBytes, totalBytes = -1, completedFiles, totalFiles = -1;
    public long currentFileBytes, currentFileSize;
    public String currentPath = "";
}
