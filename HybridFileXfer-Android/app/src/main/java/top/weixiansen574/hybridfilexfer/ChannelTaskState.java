package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.FileBlock;

/** 维护各传输通道在主线程上的当前任务与分块进度状态。 */
final class ChannelTaskState {
    enum Kind { IDLE, TASK, COMPLETE, ERROR }

    static final class Task {
        final String path;
        final boolean upload;
        final long offset, length, fileSize;

        Task(String path, boolean upload, long offset, long length, long fileSize) {
            this.path = path;
            this.upload = upload;
            this.offset = offset;
            this.length = length;
            this.fileSize = fileSize;
        }

        long blockNumber() {
            return offset == fileSize ? blockCount() : offset / FileBlock.BLOCK_SIZE + 1;
        }

        long blockCount() {
            // 断点续传的已保存前缀可能结束在某个分块中间，先折算已完成的整块数，再累加剩余待传分块数。
            long remaining = fileSize - offset;
            return offset / FileBlock.BLOCK_SIZE + remaining / FileBlock.BLOCK_SIZE
                    + (remaining % FileBlock.BLOCK_SIZE == 0 ? 0 : 1);
        }
    }

    private boolean accepting;
    private Kind kind = Kind.IDLE;
    private Task task;
    private String message = "";

    void begin() {
        accepting = true;
        clearTask();
    }

    boolean task(String path, boolean upload, long offset, long length, long fileSize) {
        if (!accepting || kind == Kind.COMPLETE || kind == Kind.ERROR || path == null
                || offset < 0 || length < 0 || fileSize < 0 || offset > fileSize
                || length > fileSize - offset) return false;
        kind = Kind.TASK;
        task = new Task(path, upload, offset, length, fileSize);
        message = "";
        return true;
    }

    boolean taskEndingAt(String path, boolean upload, long targetSize, long fileSize) {
        // 旧版回调传入的是当前分块的结束偏移量，而非已完成的分块计数。
        if (targetSize < 0) return false;
        long offset = targetSize == 0 ? 0 : (targetSize - 1) / FileBlock.BLOCK_SIZE * FileBlock.BLOCK_SIZE;
        return task(path, upload, offset, targetSize - offset, fileSize);
    }

    boolean idle() {
        // 通道已处于完成或异常状态时，忽略工作线程退出时的清理回调，避免覆盖最终状态。
        if (!accepting || kind != Kind.TASK) return false;
        clearTask();
        return true;
    }

    boolean complete(String message) {
        if (!accepting || kind == Kind.ERROR) return false;
        kind = Kind.COMPLETE;
        task = null;
        this.message = message == null ? "" : message;
        return true;
    }

    boolean error(String message) {
        if (!accepting) return false;
        kind = Kind.ERROR;
        task = null;
        this.message = message == null ? "" : message;
        return true;
    }

    void end() {
        accepting = false;
        if (kind == Kind.TASK) clearTask();
    }

    private void clearTask() {
        kind = Kind.IDLE;
        task = null;
        message = "";
    }

    Kind kind() { return kind; }
    Task task() { return task; }
    String message() { return message; }
}
