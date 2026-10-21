package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    public static final int VERSION_CODE = 401;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    private TransferOptions transferOptions = new TransferOptions();
    private boolean explicitOptions;
    private volatile ReliableTransfer activeTransfer;
    private volatile TransferSummary lastTransferSummary;

    public synchronized void setTransferOptions(TransferOptions options) {
        transferOptions = options.copy();
        explicitOptions = true;
    }
    public synchronized TransferOptions getTransferOptions() { return transferOptions.copy(); }
    public TransferSummary getLastTransferSummary() { return lastTransferSummary; }
    public void cancelTransfer() {
        ReliableTransfer transfer = activeTransfer;
        if (transfer != null) transfer.cancel();
    }
    public void stopVerification() {
        ReliableTransfer transfer = activeTransfer;
        if (transfer != null) transfer.stopVerification();
    }
    protected TransferFileAccess createFileAccess() throws IOException {
        throw new IOException("This endpoint has no filesystem adapter for protocol 401");
    }
    private synchronized ReliableTransfer begin(TransferFileCallback callback) throws IOException {
        if (activeTransfer != null) throw new IOException("A transfer is already running");
        if (ctChannel == null || connections == null) throw new IOException("Not connected");
        for (TransferConnection c : connections) { c.resetTotalTrafficInfo(); c.resetCurrentTrafficInfo(); }
        activeTransfer = new ReliableTransfer(createFileAccess(), ctChannel, connections, buffers, callback);
        return activeTransfer;
    }
    protected boolean sendFiles(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                                TransferFileCallback callback) throws IOException {
        ReliableTransfer transfer = begin(callback);
        SpeedMonitorThread monitor = new SpeedMonitorThread(connections, callback);
        monitor.setDaemon(true); monitor.start();
        try { return transfer.send(files, localDir, remoteDir, getTransferOptions()); }
        finally { end(transfer, monitor); }
    }
    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        ReliableTransfer transfer = begin(callback);
        SpeedMonitorThread monitor = new SpeedMonitorThread(connections, callback);
        monitor.setDaemon(true); monitor.start();
        boolean explicit;
        synchronized (this) { explicit = explicitOptions; }
        try { return transfer.receive(getTransferOptions(), explicit); }
        finally { end(transfer, monitor); }
    }
    private synchronized void end(ReliableTransfer transfer, SpeedMonitorThread monitor) {
        monitor.cancel();
        lastTransferSummary = transfer.getSummary();
        activeTransfer = null;
    }
    // Kept for source compatibility with platform subclasses; protocol 401 uses FileAccess.
    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int count);
    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers,
            List<RemoteFile> files, Directory localDir, Directory remoteDir, int count);
}
