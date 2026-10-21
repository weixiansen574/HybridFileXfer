package top.weixiansen574.hybridfilexfer.core.callback;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public interface TransferFileCallback {
    default void onProgress(top.weixiansen574.hybridfilexfer.core.TransferProgress progress) {}
    default void onFileResult(String path, String status, String message) {}
    default void onPhase(String phase) {}
    default void onSummary(top.weixiansen574.hybridfilexfer.core.TransferSummary summary) {}
    default void onVerificationProgress(top.weixiansen574.hybridfilexfer.core.VerificationProgress progress) {}
    default void onVerificationStopped(boolean upload, long bytes, long millis) { onIncomplete(); }
    /** An actual block assigned to this channel; offset/length describe its range, not confirmed progress. */
    default void onChannelTask(String name, String path, boolean upload, long offset, long length, long fileSize) {
        if (upload) onFileUploading(name, path, offset + length, fileSize);
        else onFileDownloading(name, path, offset + length, fileSize);
    }
    /** This channel no longer owns a file task. Completion/error callbacks remain separate. */
    default void onChannelIdle(String name) {}
    int ERROR_TYPE_EXCEPTION = 1;
    int ERROR_TYPE_INTERRUPT = 2;
    int ERROR_TYPE_READ_ERROR = 3;
    int ERROR_TYPE_WRITE_ERROR = 4;

    void onFileUploading(String iName, String path, long targetSize, long totalSize);

    void onFileDownloading(String iName, String path, long targetSize, long totalSize);

    void onSpeedInfo(List<TrafficInfo> trafficInfoList);

    void onChannelComplete(String iName, long traffic, long time);

    void onChannelError(String iName, int errorType, String message);//异常信息 or 断开

    void onReadFileError(String message);

    void onWriteFileError(String message);

    void onComplete(boolean isUpload,long traffic, long time);

    //传输通道有其中一个断开时
    void onIncomplete();

}
