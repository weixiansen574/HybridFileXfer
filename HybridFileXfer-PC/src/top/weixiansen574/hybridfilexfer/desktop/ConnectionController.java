package top.weixiansen574.hybridfilexfer.desktop;

import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;
import top.weixiansen574.hybridfilexfer.security.AuthenticationException;
import top.weixiansen574.hybridfilexfer.security.ProtocolMismatchException;
import top.weixiansen574.hybridfilexfer.security.Pairing;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static top.weixiansen574.hybridfilexfer.desktop.DesktopText.text;

/** A fresh client for every attempt; stopping never blocks the GUI event thread. */
public final class ConnectionController implements AutoCloseable {
    public enum State { IDLE, WAITING, CONNECTING, CONNECTED, TRANSFERRING, VERIFYING, STOPPING, ERROR }
    public interface Listener {
        default void onState(State state, String message) { }
        default void onLog(String message) { }
        default void onPairing(String secret, int port) { }
        default void onChannels(List<String> channels) { }
        default void onSpeed(List<TrafficInfo> traffic) { }
        default void onTransferStarted(boolean upload) { }
        default void onProgress(TransferProgress progress) { }
        default void onVerificationProgress(VerificationProgress progress) { }
        default void onVerificationStopped(boolean upload, long bytes, long millis) { }
        default void onSummary(TransferSummary summary) { }
        default void onComplete(boolean upload, long bytes, long millis) { }
        default void onTransferFailed(String message) { }
        default void onFinished() { }
    }
    public static final class Settings {
        public final String address, device, directory, secret;
        public final int remotePort, localPort;
        public final boolean reconnect, launchPhone, burst;
        public final TransferOptions transfer;
        public Settings(String address, String device, String directory, String secret, int remotePort,
                        int localPort, boolean reconnect, boolean launchPhone, boolean burst, TransferOptions transfer) {
            this.address = address.trim();
            this.device = device;
            this.directory = directory;
            this.secret = secret;
            this.remotePort = remotePort; this.localPort = localPort;
            this.reconnect = reconnect; this.launchPhone = launchPhone; this.burst = burst;
            this.transfer = transfer;
        }
        public void validate() {
            if (address.isEmpty()) throw new IllegalArgumentException(text("请输入手机 IP 地址。", "Enter the phone IP address."));
            LaunchOptions.parsePort(String.valueOf(remotePort), false);
            LaunchOptions.parsePort(String.valueOf(localPort), true);
            if (launchPhone && "adb".equals(address) && remotePort < 1024)
                throw new IllegalArgumentException(text("手机服务端口必须为 1024–65535。", "The phone service port must be 1024–65535."));
            Path path = Paths.get(directory);
            if (!directory.equals("/") && (!Files.isDirectory(path) || !Files.isWritable(path)))
                throw new IllegalArgumentException(text("请选择存在且可写入的电脑文件夹。", "Choose an existing writable folder."));
            if (secret.isEmpty() && !"adb".equals(address))
                throw new IllegalArgumentException(text("请输入手机显示的配对码。", "Enter the phone pairing code."));
            if (!secret.isEmpty()) Pairing.validateSecret(secret);
        }
    }
    private static final class Attempt {
        volatile boolean stopped;
        volatile JdkHFXClient client;
        volatile AdbBridge adb;
        volatile Thread worker;
        volatile boolean transferActive, terminalFailure, failed;
        AdbPairingSession pairing;
        final CountDownLatch done = new CountDownLatch(1);
    }
    private final Listener listener;
    private volatile Attempt current;

    public ConnectionController(Listener listener) { this.listener = listener; }
    public static String newPairingSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public void start(Settings settings) { start(settings, null); }

    /** Reuse a phone-start request made by the GUI while its confirmation is pending. */
    synchronized void start(Settings settings, AdbPairingSession preparation) {
        settings.validate();
        if (current != null && current.done.getCount() != 0) throw new IllegalStateException("A connection is already active.");
        Attempt attempt = new Attempt();
        attempt.pairing = preparation == null
                ? new AdbPairingSession(settings.secret, settings.remotePort) : preparation;
        current = attempt;
        Thread worker = new Thread(() -> work(attempt, settings), "hfx-connection");
        worker.setDaemon(true);
        attempt.worker = worker;
        worker.start();
    }
    private void state(Attempt a, State state, String message) {
        if (current == a && !a.stopped) listener.onState(state, message);
    }
    private void log(Settings settings, String message) {
        if (message == null) return;
        if (!settings.secret.isEmpty()) message = message.replace(settings.secret, "[redacted]");
        Attempt attempt = current;
        if (attempt != null) message = message.replace(attempt.pairing.secret(), "[redacted]");
        listener.onLog(message);
    }
    private void work(Attempt attempt, Settings settings) {
        int retry = 0;
        try {
            while (!attempt.stopped) {
                attempt.terminalFailure = false;
                try {
                    String host = settings.address;
                    String secret = settings.secret;
                    int remotePort = settings.remotePort, controlPort = remotePort, adbPort = 0;
                    if ("adb".equals(host)) {
                        state(attempt, State.WAITING, text("等待 USB 设备授权，请在手机上允许 USB 调试。", "Waiting for a USB device. Allow USB debugging on the phone."));
                        attempt.adb = new AdbBridge(settings.burst);
                        List<AdbBridge.Device> devices = attempt.adb.devices();
                        AdbBridge.Device selected = selectDevice(devices, settings.device);
                        boolean ready = attempt.pairing.prepare(attempt.adb, selected.serial, settings.launchPhone);
                        secret = attempt.pairing.secret();
                        remotePort = attempt.pairing.port();
                        listener.onPairing(secret, remotePort);
                        if (!ready) throw new IOException(text("等待手机确认并启动服务。", "Waiting for the phone to approve and start its service."));
                        adbPort = attempt.adb.forward(selected.serial, settings.localPort, remotePort);
                        controlPort = adbPort;
                        host = "127.0.0.1";
                    }
                    if (attempt.stopped) break;
                    JdkHFXClient client = new JdkHFXClient(host, remotePort, settings.directory);
                    attempt.client = client;
                    client.configureConnection(secret, controlPort, adbPort, 5000);
                    client.setTransferOptions(settings.transfer);
                    if (attempt.stopped) { client.close(); break; }
                    state(attempt, State.CONNECTING, text("正在连接手机…", "Connecting to phone…"));
                    if (client.connect(connectionCallbacks(attempt, settings))) {
                        retry = 0;
                        client.start(transferCallbacks(attempt, settings));
                        if (!attempt.stopped) state(attempt, State.WAITING, text("手机已断开，可在原窗口重新连接。", "Phone disconnected. This window remains ready to reconnect."));
                    } else if (attempt.terminalFailure) {
                        break;
                    }
                } catch (Exception e) {
                    if (attempt.stopped || e instanceof InterruptedException) break;
                    boolean terminal = e instanceof AuthenticationException || e instanceof ProtocolMismatchException;
                    attempt.terminalFailure |= terminal;
                    log(settings, e.toString());
                    state(attempt, terminal ? State.ERROR : State.WAITING, friendlyError(e));
                    if (attempt.transferActive) {
                        listener.onTransferFailed(text("连接中断，未完成任务已停止。重新连接后请检查并重新选择任务。", "Connection interrupted. The unfinished transfer stopped; check it before starting again."));
                        attempt.transferActive = false;
                    }
                } finally {
                    JdkHFXClient client = attempt.client;
                    attempt.client = null;
                    if (client != null) client.close();
                    AdbBridge adb = attempt.adb;
                    attempt.adb = null;
                    if (adb != null) { adb.close(); adb.removeForward(); }
                }
                if (attempt.stopped || attempt.terminalFailure || !settings.reconnect) break;
                int delay = retryDelaySeconds(retry++);
                for (int remaining = delay; remaining > 0 && !attempt.stopped; remaining--) {
                    state(attempt, State.WAITING, text("等待手机就绪 · ", "Waiting for phone · ") + remaining
                            + text(" 秒后重试（可随时停止）", "s until retry (you can stop at any time)"));
                    Thread.sleep(1000);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            attempt.terminalFailure = true;
            log(settings, error.toString());
            state(attempt, State.ERROR, friendlyError(error));
        } finally {
            if (attempt.transferActive) listener.onTransferFailed(text("传输已停止，未完成的文件不会标记为成功。", "Transfer stopped. Unfinished files are not marked successful."));
            attempt.done.countDown();
            if (current == attempt) {
                if (!attempt.terminalFailure) listener.onState(State.IDLE,
                        text("已停止。可修改设置并再次连接。", "Stopped. Update settings and connect again."));
                listener.onFinished();
            }
        }
    }

    public static int retryDelaySeconds(int attempt) { return Math.min(15, 1 << Math.min(4, Math.max(0, attempt))); }

    public static AdbBridge.Device selectDevice(List<AdbBridge.Device> devices, String serial) throws IOException {
        if (serial != null && !serial.isEmpty()) {
            for (AdbBridge.Device device : devices) if (serial.equals(device.serial)) {
                if (!device.ready()) throw new IOException(text("设备尚未授权或离线：", "Device unauthorized or offline: ") + device.state);
                return device;
            }
            throw new IOException(text("等待所选手机连接。", "Waiting for the selected phone."));
        }
        AdbBridge.Device selected = null;
        for (AdbBridge.Device device : devices) if (device.ready()) {
            if (selected != null) throw new IOException(text("检测到多台手机，请停止等待并明确选择设备。", "Several phones detected. Stop waiting and select a device."));
            selected = device;
        }
        if (selected == null) throw new IOException(text("尚无已授权的手机，请连接 USB 并在手机确认调试授权。", "No authorized phone. Connect USB and accept debugging on the phone."));
        return selected;
    }
    private ConnectServerCallback connectionCallbacks(Attempt a, Settings s) {
        return new ConnectServerCallback() {
            @Override public void onConnectingControlChannel(String address, int port) { log(s, "Connecting control channel " + address + ":" + port); }
            @Override public void onVersionMismatch(int local, int remote) {
                a.terminalFailure = true;
                state(a, State.ERROR, text("电脑与手机版本不兼容，请安装同一发布版本。", "Protocol mismatch. Install matching desktop and Android versions."));
            }
            @Override public void onConnectControlFailed() { log(s, text("手机服务尚未就绪。", "Phone service is not ready yet.")); }
            @Override public void onConnectingTransferChannel(String name, InetAddress address, InetAddress bind) { log(s, "Connecting channel " + name + " " + address.getHostAddress()); }
            @Override public void onConnectTransferChannelFailed(String name, InetAddress address, Exception error) { log(s, "Channel " + name + ": " + error); }
            @Override public void onOOM(int created, int required, long max, String arch) {
                a.terminalFailure = true;
                state(a, State.ERROR, text("电脑内存不足。请关闭其他应用、减少并行通道，并使用 64 位 Java。", "Insufficient desktop memory. Close other apps, use fewer channels, and use 64-bit Java."));
            }
            @Override public void onRemoteOOM() {
                a.terminalFailure = true;
                state(a, State.ERROR, text("手机内存不足，请关闭其他应用、减少并行通道后重试。", "Phone memory is insufficient. Close other apps or use fewer channels and retry."));
            }
            @Override public void onConnectSuccess(List<String> channels) {
                if (a.stopped) return;
                listener.onChannels(channels);
                state(a, State.CONNECTED, text("已连接 · 请在手机双栏文件列表中选择文件并点击传输。", "Connected · Select files and choose Transfer in the phone's two-pane browser."));
            }
        };
    }
    private ClientCallBack transferCallbacks(Attempt a, Settings s) {
        return new ClientCallBack() {
            private void started(boolean upload) {
                a.transferActive = true;
                a.failed = false;
                listener.onTransferStarted(upload);
                state(a, State.TRANSFERRING, upload ? text("正在发送到手机…", "Sending to phone…") : text("正在接收手机文件…", "Receiving from phone…"));
            }
            @Override public void onReceiving() { started(false); }
            @Override public void onSending() { started(true); }
            @Override public void onExit() { log(s, text("手机关闭了连接。", "Phone closed the connection.")); }
            @Override public void onFileUploading(String name, String path, long position, long size) { }
            @Override public void onFileDownloading(String name, String path, long position, long size) { }
            @Override public void onSpeedInfo(List<TrafficInfo> traffic) { if (!a.stopped) listener.onSpeed(traffic); }
            @Override public void onProgress(TransferProgress progress) { if (!a.stopped) listener.onProgress(progress); }
            @Override public void onVerificationProgress(VerificationProgress progress) {
                if (a.stopped || !a.transferActive) return;
                listener.onVerificationProgress(progress);
                state(a, State.VERIFYING, text("正在校验文件，可随时停止校验与重试。", "Verifying files. You can stop verification and retries at any time."));
            }
            @Override public void onSummary(TransferSummary summary) { listener.onSummary(summary); }
            @Override public void onPhase(String phase) { log(s, phase); }
            @Override public void onFileResult(String path, String status, String message) {
                if ("failed".equals(status)) a.failed = true;
                log(s, status + " " + path + (message == null ? "" : ": " + message));
            }
            @Override public void onChannelComplete(String name, long bytes, long time) { log(s, name + " " + DesktopText.size(bytes) + " / " + DesktopText.duration(time)); }
            @Override public void onChannelError(String name, int type, String message) { log(s, name + " error " + type + ": " + message); }
            @Override public void onReadFileError(String message) { a.failed = true; log(s, message); }
            @Override public void onWriteFileError(String message) { a.failed = true; log(s, message); }
            private void failure(String message) {
                a.failed = true;
                listener.onTransferFailed(message);
                log(s, message == null ? "Transfer failed" : message);
            }
            @Override public void onComplete(boolean upload, long bytes, long time) {
                a.transferActive = false;
                listener.onComplete(upload, bytes, time);
                state(a, State.CONNECTED, a.failed ? text("本次传输有失败文件，请查看任务摘要。", "Some files failed. Review the transfer summary.")
                        : text("传输完成，可继续在手机选择文件。", "Transfer finished. You can select more files on the phone."));
            }
            @Override public void onVerificationStopped(boolean upload, long bytes, long time) {
                a.transferActive = false;
                listener.onVerificationStopped(upload, bytes, time);
                state(a, State.CONNECTED, text("校验与重试已停止，请查看摘要；连接保持可用。",
                        "Verification and retries stopped. Review the summary; the connection remains available."));
            }
            @Override public void onIncomplete() {
                a.transferActive = false;
                failure(text("传输未完成，请检查任务摘要后重试。", "Transfer incomplete. Review the summary before retrying."));
                state(a, State.CONNECTED, text("本次任务有未完成文件，请查看摘要；连接保持可用。", "Some files were not completed. Review the summary; the connection remains available."));
            }
        };
    }
    public static String friendlyError(Throwable error) {
        if (error instanceof AuthenticationException) return text("配对失败。请核对手机配对码，然后重新连接。", "Pairing failed. Check the phone pairing code and reconnect.");
        if (error instanceof ProtocolMismatchException) return text("版本不兼容，请安装配套的电脑和手机版。", "Install matching desktop and phone versions.");
        if (error instanceof OutOfMemoryError) return text("内存不足。关闭其他应用、减少并行通道后重试。", "Not enough memory. Close other apps or use fewer channels and retry.");
        if (error instanceof java.net.ConnectException || error instanceof java.net.SocketTimeoutException)
            return text("暂时无法连接手机，请检查授权、网络及手机服务状态。", "Phone is not reachable yet. Check authorization, network, and phone service.");
        if (error instanceof java.io.EOFException) return text("手机连接已断开。", "The phone disconnected.");
        String message = error.getMessage();
        return message == null || message.isEmpty() ? text("连接出现问题，请展开诊断日志查看详情。", "Connection failed. Expand diagnostics for details.") : message;
    }
    public boolean isRunning() { Attempt a = current; return a != null && a.done.getCount() != 0; }
    public boolean await(long time, TimeUnit unit) throws InterruptedException { Attempt a = current; return a == null || a.done.await(time, unit); }
    public void cancelTransfer() {
        Attempt a = current;
        if (a != null && a.client != null) a.client.cancelTransfer();
    }
    public void stopVerification() {
        Attempt a = current;
        if (a != null && a.client != null) a.client.stopVerification();
    }
    @Override public void close() {
        Attempt a = current;
        if (a == null || a.done.getCount() == 0) return;
        a.stopped = true;
        listener.onState(State.STOPPING, text("正在停止…", "Stopping…"));
        AdbBridge adb = a.adb;
        if (adb != null) adb.close();
        JdkHFXClient client = a.client;
        if (client != null) client.close();
        if (a.worker != null) a.worker.interrupt();
    }
}
