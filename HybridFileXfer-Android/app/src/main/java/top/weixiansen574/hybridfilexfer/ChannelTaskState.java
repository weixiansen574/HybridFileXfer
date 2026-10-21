package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.FileBlock;

/** Main-thread channel state, kept separate from Android views and translated text. */
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
            // A resumed prefix may end inside a block. Count its complete blocks, then the
            // actual protocol blocks starting at that prefix; do not invent an extra tail block.
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
        // Legacy callbacks supply the block's end position, not a completed-block counter.
        if (targetSize < 0) return false;
        long offset = targetSize == 0 ? 0 : (targetSize - 1) / FileBlock.BLOCK_SIZE * FileBlock.BLOCK_SIZE;
        return task(path, upload, offset, targetSize - offset, fileSize);
    }

    boolean idle() {
        // A worker's finally callback must not erase its completion/error callback.
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
