package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.io.Closeable;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.TransferFileAccess;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.core.TransferHandleRegistry;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** No raw file contents cross Binder; descriptors and small control records do. */
public final class DroidTransferFileAccess implements TransferFileAccess {
    private final IIOService io;
    private final TransferHandleRegistry handles=new TransferHandleRegistry();
    private final java.util.concurrent.ConcurrentMap<String,String> pendingCloses=new java.util.concurrent.ConcurrentHashMap<>();
    private final Object cleanupLock=new Object();
    private java.util.concurrent.FutureTask<Void> cleanupTask;
    public DroidTransferFileAccess(IIOService io) { this.io=io; }
    @Override public void beginSession() throws IOException {
        awaitCleanup();
        long startingEpoch;
        synchronized (cleanupLock) {
            if (cleanupTask!=null && !cleanupTask.isDone()) throw new IOException("Previous transfer descriptors are still closing");
            startingEpoch=handles.invalidate();
        }
        handles.closeInvalidated(()->{});
        flushPendingCloses();
        try { io.beginTransferFileSession(); } catch (RemoteException e) { throw new IOException(e); }
        handles.beginSession(startingEpoch);
    }
    @Override public void cancelPendingOperations() {
        // UI callers only invalidate memory state and schedule one bounded worker.
        synchronized (cleanupLock) {
            handles.invalidate();
            if (cleanupTask!=null && !cleanupTask.isDone()) return;
            cleanupTask=new java.util.concurrent.FutureTask<>(()->{
                handles.closeInvalidated(()->{
                    try { io.cancelPendingTransferOperations(); } catch (RemoteException ignored) {}
                });
                return null;
            });
            Thread worker=new Thread(cleanupTask,"hfx-descriptor-cleanup");
            worker.setDaemon(true); worker.start();
        }
    }
    @Override public List<String> takeWarnings() {
        try {
            String[] warnings=io.takeTransferFileWarnings();
            return warnings==null?java.util.Collections.emptyList():java.util.Arrays.asList(warnings);
        } catch (RemoteException e) {
            return java.util.Collections.singletonList("Could not retrieve filesystem cleanup warnings: "+e);
        }
    }
    private static void checked(String error) throws IOException { if (error!=null) throw new IOException(error); }
    @Override public String canonicalPath(String path) throws IOException {
        try {
            String[] value=io.canonicalTransferPath(path);
            if (value==null || value.length!=2 || !value[1].isEmpty()) throw new IOException("Cannot resolve source path: "+path);
            return value[0];
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public RemoteFile stat(String path) throws IOException {
        try {
            String[] a=io.describeTransferFile(path);
            if (a==null || a.length!=5) throw new IOException(a!=null && a.length==1?a[0]:"Cannot stat source");
            return new RemoteFile(a[0],a[1],Long.parseLong(a[2]),Long.parseLong(a[3]),Boolean.parseBoolean(a[4]));
        } catch (RemoteException | NumberFormatException e) { throw new IOException(e); }
    }
    @Override public List<RemoteFile> list(String path) throws IOException {
        try {
            int[] chunks=io.listFiles(path);
            if (chunks==null) throw new IOException("Cannot list directory: "+path);
            List<RemoteFile> files=new ArrayList<>();
            for (int chunk:chunks) files.addAll(io.getAndRemoveFileListSlice(chunk));
            java.util.Collections.sort(files,java.util.Comparator.comparing(RemoteFile::getName));
            return files;
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Handle openRead(String path) throws IOException {
        return openTracked("",true,id->io.openTransferSource(path));
    }
    @Override public void createDirectory(String root,String relative,long modified) throws IOException {
        try { checked(io.createTransferDirectory(root,relative,modified)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Prepared prepare(String root,String relative,String identity,long size,long modified,
                                       String hash,TransferOptions options) throws IOException {
        try {
            Prepared p=Prepared.decode(io.prepareTransferFile(root,relative,identity,size,modified,hash,
                    options.conflictPolicy.ordinal(),options.resume,options.incremental));
            if ("failed".equals(p.status)) throw new IOException(p.message);
            return p;
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Handle openStage(String token) throws IOException {
        return openTracked(token,false,id->io.openTransferStage(token,id));
    }
    @Override public void checkpoint(String token,long offset) throws IOException {
        try { checked(io.checkpointTransferFile(token,offset)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void endSession() throws IOException {
        IOException failure=null;
        try { awaitCleanup(); } catch (IOException e) { failure=e; }
        try { handles.invalidateAndClose(); }
        catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
        try { flushPendingCloses(); }
        catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
        try { checked(io.endTransferFileSession()); }
        catch (RemoteException | IOException e) {
            if (failure==null) failure=new IOException(e); else failure.addSuppressed(e);
        }
        if (failure!=null) throw failure;
    }
    @Override public void seal(String token) throws IOException {
        handles.requireClosed(token);
        try { checked(io.sealTransferFile(token)); } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public Handle openSealedRead(String token) throws IOException {
        return openTracked(token,true,id->io.openSealedTransferFile(token,id));
    }
    @Override public void completeVerification(String token,String expected,String actual) throws IOException {
        handles.requireClosed(token);
        try { checked(io.completeTransferVerification(token,expected,actual)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void releaseVerification(String token) throws IOException {
        handles.requireClosed(token);
        try { checked(io.releaseTransferVerification(token)); } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void resetForRetry(String token) throws IOException {
        handles.requireClosed(token);
        try { checked(io.resetTransferFileForRetry(token)); } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public String publishUnverified(String token) throws IOException {
        handles.requireClosed(token);
        try {
            String[] result=io.publishUnverifiedTransferFile(token);
            if (result==null || result.length!=2) throw new IOException("Invalid unverified publication response");
            if (!result[1].isEmpty()) throw new IOException(result[1]);
            return result[0];
        } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void retainUnverified(String token) throws IOException {
        handles.requireClosed(token);
        try { checked(io.retainUnverifiedTransferFile(token)); } catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void commit(String token,boolean verify) throws IOException {
        handles.requireClosed(token);
        try { checked(io.commitTransferFile(token,verify)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    @Override public void abandon(String token,boolean keep) throws IOException {
        handles.requireClosed(token);
        try { checked(io.abandonTransferFile(token,keep)); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    private interface DescriptorRequest { ParcelFileDescriptor open(String handleId) throws RemoteException; }
    /** begin/end run on the transfer worker; a new task cannot overtake old descriptor cleanup. */
    private void awaitCleanup() throws IOException {
        java.util.concurrent.FutureTask<Void> pending;
        synchronized (cleanupLock) { pending=cleanupTask; }
        if (pending==null) return;
        try { pending.get(5,java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while closing transfer descriptors",e); }
        catch (java.util.concurrent.TimeoutException e) { throw new IOException("Previous transfer descriptors are still closing",e); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException("Transfer descriptor cleanup failed",e.getCause()); }
        finally {
            if (pending.isDone()) synchronized (cleanupLock) { if (cleanupTask==pending) cleanupTask=null; }
        }
    }
    private Handle openTracked(String token,boolean reader,DescriptorRequest request) throws IOException {
        long epoch=handles.beginOpen();
        String handleId=token.isEmpty()?"":java.util.UUID.randomUUID().toString();
        ParcelFileDescriptor descriptor=null;
        TrackedOwner owner=null;
        boolean registered=false;
        try {
            descriptor=request.open(handleId);
            if (descriptor==null) throw new IOException("Cannot open transfer file descriptor");
            FileChannel channel;
            if (reader) {
                ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                channel=in.getChannel(); owner=new TrackedOwner(in,token,handleId);
            } else {
                ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                channel=out.getChannel(); owner=new TrackedOwner(out,token,handleId);
            }
            if (!handles.register(epoch,token,owner)) throw new IOException("Transfer session closed while opening the file");
            registered=true;
            return new Handle(channel,owner);
        } catch (RemoteException e) { throw new IOException(e); }
        finally {
            if (!registered) {
                try {
                    if (owner!=null) owner.close();
                    else {
                        try { if (descriptor!=null) descriptor.close(); }
                        finally { acknowledgeClose(token,handleId); }
                    }
                } finally {
                    if (reader && !token.isEmpty()) {
                        try { io.releaseTransferVerification(token); } catch (RemoteException ignored) {}
                    }
                }
            }
        }
    }
    private void acknowledgeClose(String token,String handleId) throws IOException {
        if (token.isEmpty()) return;
        pendingCloses.put(handleId,token);
        try { checked(io.closeTransferHandle(token,handleId)); pendingCloses.remove(handleId,token); }
        catch (RemoteException e) { throw new IOException(e); }
    }
    private void flushPendingCloses() throws IOException {
        IOException failure=null;
        for (java.util.Map.Entry<String,String> entry:pendingCloses.entrySet()) {
            try { acknowledgeClose(entry.getValue(),entry.getKey()); }
            catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
        }
        if (failure!=null) throw failure;
    }
    private final class TrackedOwner implements Closeable {
        private final Closeable delegate;
        private final String token,handleId;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        TrackedOwner(Closeable delegate,String token,String handleId) {
            this.delegate=delegate; this.token=token; this.handleId=handleId;
        }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false,true)) return;
            IOException failure=null;
            try { delegate.close(); } catch (IOException e) { failure=e; }
            finally {
                try { acknowledgeClose(token,handleId); }
                catch (IOException e) { if (failure==null) failure=e; else failure.addSuppressed(e); }
                finally { handles.removed(this); }
            }
            if (failure!=null) throw failure;
        }
    }
}
