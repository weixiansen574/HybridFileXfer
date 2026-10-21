package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.droidcore.StartServerTask;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;

/** 进程级服务端会话管理，使连接与监听生命周期独立于 Activity 重建。 */
public final class ServerSession {
    public interface Listener { void onSessionChanged(); }
    private static ServerSession instance;
    // 主线程状态变更时同步更新不可变快照，供跨进程查询直接读取。
    private static volatile AdbPairingState adbPairingState = AdbPairingState.idle();

    static String adbPairingPayload(int uid) {
        return adbPairingState.payloadForCaller(uid);
    }

    public static synchronized ServerSession get(Context context) {
        if (instance == null) instance = new ServerSession(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final IoServiceConnector connector;
    private Listener listener;
    private HFXServer server;
    private int generation;
    public boolean active;
    public boolean connected;
    public String pairingSecret = "";
    public String message = "";
    public final Map<String, String> interfaces = new LinkedHashMap<>();

    private ServerSession(Context context) {
        this.context = context;
        connector = new IoServiceConnector(context);
    }

    public void attach(Listener listener) {
        this.listener = listener;
        notifyChanged();
    }
    public void detach(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }
    private void notifyChanged() { if (listener != null) listener.onSessionChanged(); }

    public void start(int mode, int port, String secret, List<ServerNetInterface> selected,
                      int localBuffers, int remoteBuffers) {
        if (active) return;
        AdbPairingState approvedPairing = AdbPairingState.ready(port, secret);
        active = true;
        connected = false;
        pairingSecret = secret;
        message = context.getString(R.string.starting_io_service);
        interfaces.clear();
        int attempt = ++generation;
        List<ServerNetInterface> channels = new ArrayList<>(selected);
        try {
            SessionService.acquire(context, "server", this::stop);
        } catch (RuntimeException e) {
            fail(context.getString(R.string.io_start_failed) + "\n" + e.getMessage());
            return;
        }
        // 用户已确认启动会话，此时即可公布配对信息供电脑端 ADB 发现并在 I/O 就绪后连接。
        adbPairingState = approvedPairing;
        notifyChanged();
        connector.bind(mode, new IoServiceConnector.Callback() {
            @Override public void onReady(IIOService io) {
                if (attempt != generation || !active) return;
                server = new HFXServer(io);
                server.setPairingSecret(secret);
                server.setDisconnectListener(() -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (attempt != generation || !active) return;
                    stop();
                    start(mode, port, secret, channels, localBuffers, remoteBuffers);
                }));
                new StartServerTask(new StartServerCallback() {
                    private boolean current() { return attempt == generation && active; }
                    @Override public void onBindFailed(int port) {
                        if (current()) fail(context.getString(R.string.service_start_failed, port));
                    }
                    @Override public void onStatedServer() {
                        if (!current()) return;
                        message = context.getString(R.string.deng_dai_lian_jie);
                        for (ServerNetInterface item : channels) interfaces.put(item.name, message);
                        notifyChanged();
                    }
                    @Override public void onAccepted(String name) {
                        if (!current()) return;
                        interfaces.put(name, context.getString(R.string.yi_lian_jie));
                        notifyChanged();
                    }
                    @Override public void onAcceptFailed(String name) {
                        if (!current()) return;
                        interfaces.put(name, context.getString(R.string.lian_jie_shi_bai));
                        notifyChanged();
                    }
                    @Override public void onPcOOM() {
                        if (current()) fail(context.getString(R.string.dui_fang_nei_cun_bu_zu));
                    }
                    @Override public void onMeOOM(int count, int total) {
                        if (current()) fail(context.getString(R.string.buffer_block_creation_failed_toast, count, total));
                    }
                    @Override public void onConnectSuccess() {
                        if (!current()) return;
                        adbPairingState = adbPairingState.connected();
                        connected = true;
                        HFXServer.instance = server;
                        message = context.getString(R.string.yi_lian_jie);
                        notifyChanged();
                    }
                    @Override public void onError(Throwable error) {
                        if (current()) fail(context.getString(R.string.io_disconnected) + "\n" + error.getMessage());
                    }
                }, server, port, channels, localBuffers, remoteBuffers).execute();
            }
            @Override public void onFailure(String error) {
                if (attempt == generation) fail(error);
            }
        });
    }

    private void fail(String error) {
        stop();
        message = error;
        notifyChanged();
    }

    public void stop() {
        // 在执行可能阻塞的通道与服务清理前，先清空对外公布的配对快照。
        adbPairingState = adbPairingState.stopped();
        generation++;
        HFXServer closing = server;
        server = null;
        HFXServer.instance = null;
        active = false;
        connected = false;
        pairingSecret = "";
        interfaces.clear();
        message = context.getString(R.string.fu_wu_yi_ting_zhi);
        if (closing != null) {
            closing.close();
        }
        connector.close();
        SessionService.release(context, "server");
        notifyChanged();
    }
}
