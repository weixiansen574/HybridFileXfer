package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Tracks client-owned descriptors without holding its monitor across close or Binder RPC. */
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
    /** Memory-only cancellation boundary; safe on an Android UI thread. */
    public synchronized long invalidate() { closing=true; return ++epoch; }
    /** Runs the nonblocking remote cancellation signal after invalidation, before closing descriptors. */
    public void invalidateAndClose(Runnable beforeClose) throws IOException {
        invalidate();
        closeInvalidated(beforeClose);
    }
    /** Called by a worker after invalidate; never performs I/O while holding the monitor. */
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
