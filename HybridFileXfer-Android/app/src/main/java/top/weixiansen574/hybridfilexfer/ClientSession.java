package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.droidcore.DroidHFXClient;
import top.weixiansen574.hybridfilexfer.tasks.ClientTask;
import top.weixiansen574.hybridfilexfer.tasks.ConnectServerTask;

/** Receiving-side session survives closing/recreating its status Activity. */
public final class ClientSession {
    public interface Listener { void onSessionChanged(); }
    public static final class Channel {
        public String event = "";
        public long uploadSpeed, downloadSpeed;
        private final ChannelTaskState taskState = new ChannelTaskState();
    }
    private static ClientSession instance;
    public static synchronized ClientSession get(Context context) {
        if (instance == null) instance = new ClientSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final IoServiceConnector connector;
    private DroidHFXClient client;
    private Listener listener;
    private int generation;
    private boolean transferRunning;
    private String lastTransferReport = "";
    private TransferSummary lastTransferSummary;
    final VerificationUiState verification = new VerificationUiState();
    public boolean active;
    public boolean connecting;
    public String state = "";
    public final Map<String, Channel> channels = new LinkedHashMap<>();

    private ClientSession(Context context) {
        this.context = context;
        connector = new IoServiceConnector(context);
    }
    public void attach(Listener listener) { this.listener = listener; changed(); }
    public void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    private void changed() { if (listener != null) listener.onSessionChanged(); }
    private void state(String message) { state = message; changed(); }

    public void start(int mode, String address, int port, String home, String pairing) {
        if (active) return;
        int attempt = ++generation;
        active = true;
        connecting = true;
        lastTransferReport = "";
        transferRunning = false;
        channels.clear();
        state(context.getString(R.string.starting_io_service));
        try {
            SessionService.acquire(context, "client", this::stop);
        } catch (RuntimeException e) {
            fail(e.toString());
            return;
        }
        connector.bind(mode, new IoServiceConnector.Callback() {
            @Override public void onReady(IIOService io) {
                if (attempt != generation || !active) return;
                client = new DroidHFXClient(address, port, home, io, context);
                client.configureConnection(pairing, port, port, 10000);
                new ConnectServerTask(new ConnectServerTask.Callback() {
                    private boolean current() { return generation == attempt && active; }
                    @Override public void onConnectSuccess(List<String> names) {
                        if (!current()) return;
                        connecting = false;
                        for (String name : names) {
                            Channel channel = new Channel();
                            channel.event = TransferSession.channelText(context, channel.taskState);
                            channels.put(name, channel);
                        }
                        state(context.getString(R.string.waiting_for_tasks));
                        runClient(attempt);
                    }
                    @Override public void onConnectingControlChannel(String address, int port) {
                        if (current()) state(context.getString(R.string.connecting_controller, address));
                    }
                    @Override public void onVersionMismatch(int local, int remote) {
                        if (current()) fail(context.getString(R.string.Inconsistent_protocol_versions, local, remote));
                    }
                    @Override public void onConnectControlFailed() {
                        if (current()) fail(context.getString(R.string.control_channel_connection_failed));
                    }
                    @Override public void onConnectingTransferChannel(String name, InetAddress address, InetAddress bind) {
                        if (current()) state(context.getString(R.string.connecting_transfer_channel, name,
                                address.getHostAddress(), bind == null ? "" : bind.getHostAddress()));
                    }
                    @Override public void onConnectTransferChannelFailed(String name, InetAddress address, Exception error) {
                        if (current()) fail(error.toString());
                    }
                    @Override public void onOOM(int count, int total, long maxMemory, String arch) {
                        if (current()) fail(context.getString(R.string.failed_to_create_buffer_block, count, total, maxMemory));
                    }
                    @Override public void onRemoteOOM() {
                        if (current()) fail(context.getString(R.string.c_remote_oom_message));
                    }
                    @Override public void onError(Throwable error) {
                        if (current()) fail(error.toString());
                    }
                }, client).execute();
            }
            @Override public void onFailure(String error) {
                if (attempt == generation) fail(error);
            }
        });
    }

    private void runClient(int attempt) {
        new ClientTask(new ClientTask.Callback() {
            private boolean current() { return generation == attempt && active; }
            private Channel channel(String name) {
                return current() && transferRunning ? channels.get(name) : null;
            }
            private void updateChannel(Channel channel) {
                channel.event = TransferSession.channelText(context, channel.taskState);
                changed();
            }
            private void beginTransfer(int message) {
                if (!current()) return;
                transferRunning = true;
                lastTransferReport = "";
                lastTransferSummary = null;
                verification.begin();
                for (Channel channel : channels.values()) {
                    channel.taskState.begin();
                    channel.event = TransferSession.channelText(context, channel.taskState);
                    channel.uploadSpeed = channel.downloadSpeed = 0;
                }
                state(context.getString(message));
            }
            @Override public void onReceiving() {
                beginTransfer(R.string.zheng_zai_jie_shou_wen_jian);
            }
            @Override public void onSending() {
                beginTransfer(R.string.zheng_zai_fa_song_wen_jian);
            }
            @Override public void onExit() { if (current()) stop(); }
            @Override public void onFileUploading(String name, String path, long size, long total) {
                if (!verification.transferringData()) return;
                Channel channel = channel(name);
                if (channel != null && channel.taskState.taskEndingAt(path, true, size, total)) updateChannel(channel);
            }
            @Override public void onFileDownloading(String name, String path, long size, long total) {
                if (!verification.transferringData()) return;
                Channel channel = channel(name);
                if (channel != null && channel.taskState.taskEndingAt(path, false, size, total)) updateChannel(channel);
            }
            @Override public void onChannelTask(String name, String path, boolean upload,
                                                long offset, long length, long fileSize) {
                if (!verification.transferringData()) return;
                Channel channel = channel(name);
                if (channel != null && channel.taskState.task(path, upload, offset, length, fileSize)) {
                    updateChannel(channel);
                }
            }
            @Override public void onChannelIdle(String name) {
                Channel channel = channel(name);
                if (channel != null && channel.taskState.idle()) updateChannel(channel);
            }
            @Override public void onSpeedInfo(List<TrafficInfo> traffic) {
                if (!current() || !transferRunning) return;
                for (TrafficInfo info : traffic) {
                    Channel channel = channels.get(info.iName);
                    if (channel != null) {
                        boolean finished = channel.taskState.kind() == ChannelTaskState.Kind.COMPLETE
                                || channel.taskState.kind() == ChannelTaskState.Kind.ERROR
                                || !verification.transferringData();
                        channel.uploadSpeed = finished ? 0 : info.uploadTraffic;
                        channel.downloadSpeed = finished ? 0 : info.downloadTraffic;
                    }
                }
                changed();
            }
            @Override public void onChannelComplete(String name, long traffic, long time) {
                Channel channel = channel(name);
                if (channel != null && channel.taskState.complete(context.getString(R.string.transfer_completed,
                        Utils.formatSpeed(traffic * 1000 / Math.max(1, time))))) {
                    channel.uploadSpeed = channel.downloadSpeed = 0;
                    updateChannel(channel);
                }
            }
            @Override public void onChannelError(String name, int type, String message) {
                Channel channel = channel(name);
                if (channel != null && channel.taskState.error(message)) {
                    channel.uploadSpeed = channel.downloadSpeed = 0;
                    updateChannel(channel);
                }
            }
            @Override public void onReadFileError(String message) { if (current() && transferRunning) state(message); }
            @Override public void onWriteFileError(String message) { if (current() && transferRunning) state(message); }
            @Override public void onComplete(boolean upload, long traffic, long time) {
                if (!current() || !transferRunning) return;
                endTransfer();
                state((upload ? "↑ " : "↓ ") + Utils.formatFileSize(traffic) + " · " + Utils.formatTime(time)
                        + "\n" + lastTransferReport);
            }
            @Override public void onVerificationStopped(boolean upload, long traffic, long time) {
                if (!current() || !transferRunning) return;
                endTransfer();
                state(TransferSession.verificationStoppedText(context, lastTransferSummary) + "\n"
                        + (upload ? "↑ " : "↓ ") + Utils.formatFileSize(traffic) + " · " + Utils.formatTime(time)
                        + "\n" + lastTransferReport);
            }
            @Override public void onProgress(top.weixiansen574.hybridfilexfer.core.TransferProgress progress) {
                if (current() && transferRunning && !verification.active()) state(Utils.formatFileSize(progress.transferredBytes) + " / "
                        + (progress.totalBytes < 0 ? "?" : Utils.formatFileSize(progress.totalBytes))
                        + " · " + progress.completedFiles + " / " + (progress.totalFiles < 0 ? "?" : progress.totalFiles)
                        + "\n" + progress.currentPath);
            }
            @Override public void onPhase(String phase) {
                if (current() && transferRunning && !verification.active()) state(phase);
            }
            @Override public void onVerificationProgress(VerificationProgress progress) {
                if (!current() || !transferRunning || !verification.update(progress)) return;
                if (!"retrying".equals(progress.phase)) {
                    for (Channel channel : channels.values()) {
                        channel.taskState.idle();
                        channel.event = TransferSession.channelText(context, channel.taskState);
                        channel.uploadSpeed = channel.downloadSpeed = 0;
                    }
                }
                state(TransferSession.verificationText(context, progress, verification.stopping()));
            }
            @Override public void onSummary(top.weixiansen574.hybridfilexfer.core.TransferSummary summary) {
                if (!current() || !transferRunning) return;
                lastTransferSummary = summary;
                lastTransferReport = TransferSession.formatSummary(context, summary);
                LocalHistory.transfer(context, lastTransferReport);
            }
            @Override public void onIncomplete() {
                if (!current() || !transferRunning) return;
                endTransfer();
                state(context.getString(R.string.chuan_shu_shi_fa_sheng_yi_chang) + "\n" + lastTransferReport);
            }
            @Override public void onComplete() {
                // This is task completion, distinct from one completed transfer above.
                if (current()) stop();
            }
            @Override public void onError(Throwable error) { if (current()) fail(error.toString()); }
        }, client).execute();
    }

    private void endTransfer() {
        transferRunning = false;
        verification.finish();
        for (Channel channel : channels.values()) {
            channel.taskState.end();
            channel.event = TransferSession.channelText(context, channel.taskState);
            channel.uploadSpeed = channel.downloadSpeed = 0;
        }
    }

    public void stopVerification() {
        if (!active || !transferRunning || client == null || !verification.requestStop()) return;
        client.stopVerification();
        state(TransferSession.verificationText(context, verification.progress(), true));
    }

    private void fail(String message) { stop(); state(message); }
    public void stop() {
        generation++;
        active = false;
        connecting = false;
        endTransfer();
        if (client != null) {
            client.close();
            client = null;
        }
        connector.close();
        SessionService.release(context, "client");
        state(context.getString(R.string.lian_jie_yi_guan_bi));
    }
}
