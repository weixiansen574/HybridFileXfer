package top.weixiansen574.hybridfilexfer.security;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** SocketChannel 的阻塞读写不受 SO_TIMEOUT 控制，通过定时任务在握手超时后主动关闭通道。 */
public final class HandshakeDeadline implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMER;
    static {
        TIMER = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "HFX-handshake-deadline");
            thread.setDaemon(true);
            return thread;
        });
        TIMER.setRemoveOnCancelPolicy(true);
    }
    private final ScheduledFuture<?> timeout;
    public HandshakeDeadline(Closeable channel, int milliseconds) {
        timeout = TIMER.schedule(() -> {
            try { channel.close(); } catch (IOException ignored) { }
        }, milliseconds, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { timeout.cancel(false); }
}
