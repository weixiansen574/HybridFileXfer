package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 跟踪客户端持有的文件描述符与任务代际（Epoch），在同步锁外执行实际的句柄关闭与跨进程调用。 */
public final class TransferHandleRegistry {
    private final Map<Closeable,String> handles=new HashMap<>();
    private long epoch;
    private boolean closing;

    public synchronized long beginOpen() throws IOException {
        if (closing) throw new IOException("Transfer session is closing");
        return epoch;
    }
    public synchronized boolean register(long openingEpoch,String token,Closeable owner) {
        if (closing || openingEpoch!=epoch) return false;
        handles.put(owner,token);
        return true;
    }
    public synchronized void removed(Closeable owner) { handles.remove(owner); }
    public synchronized void requireClosed(String token) throws IOException {
        if (handles.containsValue(token)) throw new IOException("Close file descriptors before changing the staging state");
    }
    public synchronized void beginSession() throws IOException {
        beginSession(epoch);
    }
    public synchronized void beginSession(long expectedEpoch) throws IOException {
        if (expectedEpoch!=epoch) throw new IOException("Transfer start was cancelled while opening the session");
        if (!handles.isEmpty()) throw new IOException("Previous transfer descriptors are still closing");
        epoch++;
        closing=false;
    }
    public void invalidateAndClose() throws IOException {
        invalidateAndClose(()->{});
    }
    /** 仅更新内存中的代际状态以标记取消，可在 Android 主线程安全调用。 */
    public synchronized long invalidate() { closing=true; return ++epoch; }
    /** 在关闭本地描述符前向远端服务发送取消通知。 */
    public void invalidateAndClose(Runnable beforeClose) throws IOException {
        invalidate();
        closeInvalidated(beforeClose);
    }
    /** 由后台工作线程在标记取消后调用，负责关闭已登记的句柄并等待远端清理完成。 */
    public void closeInvalidated(Runnable beforeClose) throws IOException {
        ArrayList<Closeable> snapshot;
        synchronized (this) {
            if (!closing) throw new IOException("Invalidate the session before closing descriptors");
            snapshot=new ArrayList<>(handles.keySet());
        }
        IOException failure=null;
        try { beforeClose.run(); } catch (RuntimeException e) { failure=new IOException("Cancellation signal failed",e); }
        for (Closeable owner:snapshot) {
            try { owner.close(); }
            catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
            catch (RuntimeException e) { if (failure==null) failure=new IOException(e); else failure.addSuppressed(e); }
            finally { removed(owner); }
        }
        if (failure!=null) throw failure;
    }
}
