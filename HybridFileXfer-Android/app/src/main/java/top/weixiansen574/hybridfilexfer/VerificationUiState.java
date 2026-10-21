package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.VerificationProgress;

/** Main-thread verification lifecycle, independent of views and translated text. */
final class VerificationUiState {
    private boolean running;
    private boolean stopRequested;
    private VerificationProgress progress;

    void begin() {
        running = true;
        stopRequested = false;
        progress = null;
    }

    boolean update(VerificationProgress value) {
        if (!running || value == null || (progress != null && value.round < progress.round)) return false;
        progress = value;
        if ("stopping".equals(value.phase)) stopRequested = true;
        return true;
    }

    boolean requestStop() {
        if (!canStop()) return false;
        stopRequested = true;
        return true;
    }

    void finish() { running = false; }
    boolean active() { return running && progress != null; }
    boolean canStop() { return active() && !stopRequested; }
    boolean stopping() { return stopRequested; }
    boolean transferringData() { return !active() || "retrying".equals(progress.phase); }
    VerificationProgress progress() { return progress; }
    long uncheckedFiles() {
        return progress == null ? 0 : Math.max(0, progress.totalFiles - progress.checkedFiles);
    }
}
