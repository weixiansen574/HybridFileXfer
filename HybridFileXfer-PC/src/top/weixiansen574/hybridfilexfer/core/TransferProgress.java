package top.weixiansen574.hybridfilexfer.core;

/** Confirmed destination bytes, not the largest block offset seen on one channel. */
public final class TransferProgress {
    public long transferredBytes, totalBytes = -1, completedFiles, totalFiles = -1;
    public long currentFileBytes, currentFileSize;
    public String currentPath = "";
}
