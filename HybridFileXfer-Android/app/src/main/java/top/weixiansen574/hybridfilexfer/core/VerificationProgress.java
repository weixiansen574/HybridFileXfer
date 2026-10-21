package top.weixiansen574.hybridfilexfer.core;

/** 当前校验轮次的进度快照；仅当两端均完成哈希并比对完毕后才计入 checkedFiles。 */
public final class VerificationProgress {
    public final int round;
    public final long totalFiles, checkedFiles, verifiedFiles, failedFiles;
    public final String phase, currentPath;

    public VerificationProgress(int round, long totalFiles, long checkedFiles,
                                long verifiedFiles, long failedFiles, String phase, String currentPath) {
        this.round = round;
        this.totalFiles = totalFiles;
        this.checkedFiles = checkedFiles;
        this.verifiedFiles = verifiedFiles;
        this.failedFiles = failedFiles;
        this.phase = phase;
        this.currentPath = currentPath;
    }
}
