package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.tasks.BTransferFileCallback;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToRemoteTask;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToShelfTask;

/** Keeps worker callbacks independent of windows, including while the UI is in the background. */
public final class TransferSession implements BTransferFileCallback {
    public interface Listener { void onTransferChanged(); }
    private static TransferSession current;
    public static TransferSession current() { return current; }
    public static TransferSession start(Context context, HFXServer server, boolean upload,
                                        List<RemoteFile> files, Directory local, Directory remote,
                                        TransferOptions options) {
        if (current != null && current.running) throw new IllegalStateException("A transfer is already running");
        TransferSession session = new TransferSession(context, server, upload, local, remote);
        current = session;
        server.setTransferOptions(options);
        if (upload) new SendFilesToRemoteTask(session, server, files, local, remote).execute();
        else new SendFilesToShelfTask(session, server, files, local, remote).execute();
        return session;
    }
    private final Context context;
    private final HFXServer server;
    private final String source, destination;
    private Listener listener;
    private final Map<String, ChannelTaskState> channelStates = new LinkedHashMap<>();
    final VerificationUiState verification = new VerificationUiState();
    private boolean cancelling;
    private final long startedAt = android.os.SystemClock.elapsedRealtime();
    public final boolean upload;
    public final List<String> channelNames;
    public final Map<String, String> events = new LinkedHashMap<>();
    public List<TrafficInfo> speeds = new ArrayList<>();
    public TransferProgress progress;
    public TransferSummary summary;
    public boolean running = true;
    public boolean acknowledged;
    public String status = "";
    public long traffic, elapsed;

    private TransferSession(Context context, HFXServer server, boolean upload, Directory local, Directory remote) {
        this.context = context.getApplicationContext();
        this.server = server;
        this.upload = upload;
        verification.begin();
        source = upload ? local.path : remote.path;
        destination = upload ? remote.path : local.path;
        channelNames = server.getConnectionListINames();
        for (String name : channelNames) {
            ChannelTaskState channel = new ChannelTaskState();
            channel.begin();
            channelStates.put(name, channel);
            events.put(name, channelText(context, channel));
        }
    }
    public void attach(Listener listener) { this.listener = listener; changed(); }
    public void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    private void changed() { if (listener != null) listener.onTransferChanged(); }
    public void cancel() {
        // A click queued while the dialog changes phase must never abort the
        // transport after the action has become "stop verification and retries".
        if (verification.active()) {
            stopVerification();
            return;
        }
        if (running && !cancelling) {
            cancelling = true;
            endChannels();
            status = context.getString(R.string.cancelling_transfer);
            server.cancelTransfer();
            changed();
        }
    }
    public void stopVerification() {
        if (!running || !verification.requestStop()) return;
        server.stopVerification();
        changed();
    }
    @Override public void onFileUploading(String name, String path, long size, long total) {
        if (!verification.transferringData()) return;
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.taskEndingAt(path, true, size, total)) updateChannel(name, channel);
    }
    @Override public void onFileDownloading(String name, String path, long size, long total) {
        if (!verification.transferringData()) return;
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.taskEndingAt(path, false, size, total)) updateChannel(name, channel);
    }
    @Override public void onChannelTask(String name, String path, boolean upload,
                                        long offset, long length, long fileSize) {
        if (!verification.transferringData()) return;
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.task(path, upload, offset, length, fileSize)) updateChannel(name, channel);
    }
    @Override public void onChannelIdle(String name) {
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.idle()) updateChannel(name, channel);
    }
    @Override public void onSpeedInfo(List<TrafficInfo> speeds) {
        if (!running || cancelling) return;
        this.speeds = new ArrayList<>();
        for (TrafficInfo speed : speeds) {
            ChannelTaskState channel = channelStates.get(speed.iName);
            if (channel == null) continue;
            boolean finished = channel.kind() == ChannelTaskState.Kind.COMPLETE
                    || channel.kind() == ChannelTaskState.Kind.ERROR || !verification.transferringData();
            this.speeds.add(new TrafficInfo(speed.iName,
                    finished ? 0 : speed.uploadTraffic, finished ? 0 : speed.downloadTraffic));
        }
        changed();
    }
    @Override public void onChannelComplete(String name, long traffic, long time) {
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.complete(context.getString(R.string.transfer_completed,
                Utils.formatSpeed(traffic * 1000 / Math.max(1, time))))) updateChannel(name, channel);
    }
    @Override public void onChannelError(String name, int type, String message) {
        ChannelTaskState channel = channelStates.get(name);
        if (channel != null && channel.error(message)) updateChannel(name, channel);
    }
    private void updateChannel(String name, ChannelTaskState channel) {
        events.put(name, channelText(context, channel));
        if (channel.kind() == ChannelTaskState.Kind.COMPLETE || channel.kind() == ChannelTaskState.Kind.ERROR) {
            List<TrafficInfo> updatedSpeeds = new ArrayList<>();
            for (TrafficInfo speed : speeds) {
                if (!name.equals(speed.iName)) updatedSpeeds.add(speed);
            }
            updatedSpeeds.add(new TrafficInfo(name, 0, 0));
            speeds = updatedSpeeds;
        }
        changed();
    }
    private void endChannels() {
        speeds = new ArrayList<>();
        for (Map.Entry<String, ChannelTaskState> entry : channelStates.entrySet()) {
            entry.getValue().end();
            events.put(entry.getKey(), channelText(context, entry.getValue()));
            speeds.add(new TrafficInfo(entry.getKey(), 0, 0));
        }
    }
    static String channelText(Context context, ChannelTaskState channel) {
        if (channel.kind() == ChannelTaskState.Kind.TASK) {
            ChannelTaskState.Task task = channel.task();
            return context.getString(task.upload ? R.string.channel_task_upload : R.string.channel_task_download,
                    task.path, task.blockNumber(), task.blockCount());
        }
        return channel.kind() == ChannelTaskState.Kind.IDLE
                ? context.getString(R.string.dang_qian_wu_ren_wu) : channel.message();
    }
    @Override public void onReadFileError(String message) { if (running && !cancelling) { status = message; changed(); } }
    @Override public void onWriteFileError(String message) { if (running && !cancelling) { status = message; changed(); } }
    @Override public void onProgress(TransferProgress progress) {
        if (!running || cancelling) return;
        this.progress = progress;
        changed();
    }
    @Override public void onPhase(String phase) {
        if (running && !cancelling && !verification.active()) { status = phase; changed(); }
    }
    @Override public void onVerificationProgress(VerificationProgress value) {
        if (!running || cancelling || !verification.update(value)) return;
        if (!"retrying".equals(value.phase)) {
            speeds = new ArrayList<>();
            for (Map.Entry<String, ChannelTaskState> entry : channelStates.entrySet()) {
                entry.getValue().idle();
                events.put(entry.getKey(), channelText(context, entry.getValue()));
                speeds.add(new TrafficInfo(entry.getKey(), 0, 0));
            }
        }
        changed();
    }
    @Override public void onFileResult(String path, String result, String message) {
        if (!running || cancelling) return;
        if (!verification.active()) {
            status = result + ": " + path + (message == null || message.isEmpty() ? "" : "\n" + message);
        }
        changed();
    }
    @Override public void onSummary(TransferSummary summary) {
        if (!running) return;
        this.summary = summary;
        changed();
    }
    @Override public void onComplete(boolean upload, long traffic, long time) {
        if (!running) return;
        this.traffic = traffic;
        elapsed = time;
        finish(context.getString(R.string.transfer_finished));
    }
    @Override public void onVerificationStopped(boolean upload, long traffic, long time) {
        if (!running) return;
        this.traffic = traffic;
        elapsed = time;
        finish(verificationStoppedText(context, summary));
    }
    @Override public void onIncomplete() { finish(context.getString(R.string.chuan_shu_shi_fa_sheng_yi_chang)); }
    @Override public void onError(Throwable error) { finish(error.toString()); }

    public String resultText() {
        String text = status + "\n" + source + " → " + destination + "\n"
                + Utils.formatFileSize(traffic) + " · " + Utils.formatTime(elapsed);
        if (summary != null) text += "\n" + formatSummary(context, summary);
        return text;
    }

    static String formatSummary(Context context, TransferSummary summary) {
        StringBuilder text = new StringBuilder(context.getString(R.string.transfer_result_counts,
                summary.completedFiles, summary.skippedFiles, summary.failedFiles));
        if (summary.cancelled) text.append('\n').append(context.getString(R.string.transfer_cancelled));
        if (summary.verificationEnabled) {
            text.append('\n').append(context.getString(R.string.verification_result_counts,
                    summary.verifiedFiles, summary.verificationFailedFiles, summary.unverifiedFiles));
        } else {
            text.append('\n').append(context.getString(R.string.verification_disabled));
        }
        if (summary.verificationRounds > 0) {
            text.append('\n').append(context.getString(R.string.verification_retry_counts,
                    summary.verificationRounds, summary.retriedFiles));
        }
        if (summary.verificationStopped) {
            text.append('\n').append(verificationStoppedText(context, summary));
        }
        if (summary.unverifiedFiles > 0) {
            text.append('\n').append(context.getString(R.string.verification_unchecked_note));
        }
        for (String failure : summary.failures) text.append('\n').append(failure);
        if (!summary.warnings.isEmpty()) {
            text.append('\n').append(context.getString(R.string.transfer_cleanup_warnings, summary.warnings.size()));
            for (String warning : summary.warnings) text.append('\n').append(warning);
        }
        return text.toString();
    }
    static String verificationStoppedText(Context context, TransferSummary summary) {
        return context.getString(summary != null
                && (summary.verificationFailedFiles > 0 || summary.failedFiles > 0)
                ? R.string.verification_stopped_with_failures : R.string.verification_stopped);
    }
    static String verificationPhase(Context context, VerificationProgress progress, boolean stopping) {
        if (stopping || "stopping".equals(progress.phase)) return context.getString(R.string.verification_stopping);
        if ("retrying".equals(progress.phase)) return context.getString(R.string.verification_retrying);
        if ("waiting-peer".equals(progress.phase)) return context.getString(R.string.verification_waiting_peer);
        return context.getString(R.string.verification_running);
    }
    static String verificationText(Context context, VerificationProgress progress, boolean stopping) {
        String text = context.getString(R.string.verification_round_status, progress.round,
                verificationPhase(context, progress, stopping)) + "\n"
                + context.getString(R.string.verification_live_counts, progress.verifiedFiles,
                progress.failedFiles, Math.max(0, progress.totalFiles - progress.checkedFiles));
        if (progress.currentPath != null && !progress.currentPath.isEmpty()) text += "\n" + progress.currentPath;
        return text;
    }
    private void finish(String message) {
        if (!running) return;
        running = false;
        verification.finish();
        endChannels();
        if (summary != null) traffic = summary.transferredBytes;
        else if (progress != null) traffic = progress.transferredBytes;
        if (elapsed == 0) elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
        status = message;
        LocalHistory.transfer(context, resultText());
        changed();
    }
}
