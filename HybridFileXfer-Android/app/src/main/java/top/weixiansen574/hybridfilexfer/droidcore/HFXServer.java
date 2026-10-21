package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.RemoteException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;
import top.weixiansen574.hybridfilexfer.security.Pairing;
import top.weixiansen574.hybridfilexfer.security.HandshakeDeadline;
import top.weixiansen574.hybridfilexfer.core.TransferFileAccess;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.ReceiveFileCall;
import top.weixiansen574.hybridfilexfer.core.SendFileCall;
import top.weixiansen574.hybridfilexfer.core.SpeedMonitorThread;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class HFXServer extends HFXService {
    public static HFXServer instance;
    protected final IIOService ioService;
    protected volatile ServerSocketChannel serverSocketChannel;
    private volatile boolean closed;
    private volatile String pairingSecret = Pairing.newSecret();
    private volatile Runnable disconnectListener;
    private ScheduledThreadPoolExecutor heartbeat;
    private final Object lifecycleLock = new Object();
    private final ReentrantLock operationLock = new ReentrantLock();
    private final List<SocketChannel> openedChannels = Collections.synchronizedList(new ArrayList<SocketChannel>());
    protected int remoteFileSystem;
    protected String remoteHomeDir;

    public HFXServer(IIOService ioService) {
        this.ioService = ioService;
    }

    public void setPairingSecret(String secret) { pairingSecret = Pairing.validateSecret(secret); }
    public String getPairingSecret() { return pairingSecret; }
    public void setDisconnectListener(Runnable listener) { disconnectListener = listener; }

    private SocketChannel acceptChannel(int timeoutMs) throws IOException {
        long until = timeoutMs <= 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try (Selector selector = Selector.open()) {
            ServerSocketChannel listener = serverSocketChannel;
            if (listener == null || closed) throw new IOException("Server stopped");
            listener.register(selector, SelectionKey.OP_ACCEPT);
            while (!closed) {
                SocketChannel channel = listener.accept();
                if (channel != null) {
                    channel.configureBlocking(true);
                    channel.socket().setTcpNoDelay(true);
                    channel.socket().setKeepAlive(true);
                    synchronized (openedChannels) {
                        if (closed) { channel.close(); throw new IOException("Server stopped"); }
                        openedChannels.add(channel);
                    }
                    return channel;
                }
                long remaining = until - System.nanoTime();
                if (remaining <= 0) throw new java.net.SocketTimeoutException("Timed out waiting for data channel");
                selector.select(Math.min(1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                selector.selectedKeys().clear();
            }
            throw new IOException("Server stopped");
        }
    }

    private void closeAcceptedChannels() {
        synchronized (openedChannels) {
            for (SocketChannel channel : openedChannels) {
                try { channel.close(); } catch (IOException ignored) { }
            }
            openedChannels.clear();
        }
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount,
                            int remoteBufferCount, StartServerCallback callback) throws IOException {
        if (port < 1 || port > 65535 || interfaceList.isEmpty() || interfaceList.size() > 16)
            throw new IOException("Choose a valid port and between 1 and 16 channels");
        if (closed) return;
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress(port));
            serverSocketChannel.configureBlocking(false);
        } catch (IOException error) {
            close(); callback.onBindFailed(port); return;
        }
        if (closed) { serverSocketChannel.close(); return; }
        callback.onStatedServer();
        int failures = 0;
        long failureWindow = System.nanoTime();
        while (!closed) {
            try {
                SocketChannel control = acceptChannel(0);
                try (HandshakeDeadline deadline = new HandshakeDeadline(control, 60000)) {
                    DataByteChannel raw = new DataByteChannel(control);
                    try (HandshakeDeadline proofDeadline = new HandshakeDeadline(control, 10000)) {
                        byte[] header = new byte[4];
                        raw.readFully(header);
                        if (!Arrays.equals(CLIENT_HEADER.getBytes(StandardCharsets.UTF_8), header))
                            throw new IOException("Unknown client protocol");
                        int version = raw.readInt();
                        if (version != VERSION_CODE) {
                            raw.writeBoolean(false); raw.writeInt(VERSION_CODE);
                            throw new IOException("Client version mismatch");
                        }
                        raw.writeBoolean(true);
                        if (System.nanoTime() - failureWindow > TimeUnit.MINUTES.toNanos(1)) {
                            failures = 0; failureWindow = System.nanoTime();
                        }
                        if (failures >= 10) throw new IOException("Too many rejected pairing attempts; wait a minute");
                        Pairing.Session session = Pairing.server(raw, pairingSecret);
                        this.ctChannel = session.channel;
                        ctChannel.writeInt(interfaceList.size());
                        for (ServerNetInterface net : interfaceList) {
                            ctChannel.writeUTF(net.name);
                            byte[] address = net.address.getAddress();
                            ctChannel.writeByte(address.length); ctChannel.write(address);
                            if (net.clientBindAddress == null) ctChannel.writeByte(0);
                            else {
                                address = net.clientBindAddress.getAddress();
                                ctChannel.writeByte(address.length); ctChannel.write(address);
                            }
                        }
                        // Proof completed; data channels each have their own bounded handshake.
                        proofDeadline.close();
                        connections = new ArrayList<>();
                        for (int i = 0; i < interfaceList.size(); i++) {
                            if (!ctChannel.readBoolean()) {
                                String name = ctChannel.readUTF();
                                callback.onAcceptFailed(name);
                                throw new IOException("Client could not connect selected channel");
                            }
                            String name = ctChannel.readUTF();
                            if (!name.equals(interfaceList.get(i).name)) throw new IOException("Channel order mismatch");
                            long dataDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            boolean accepted = false;
                            while (!accepted && !closed) {
                                int remaining = (int) TimeUnit.NANOSECONDS.toMillis(dataDeadline - System.nanoTime());
                                if (remaining <= 0) throw new IOException("Data channel pairing timed out");
                                SocketChannel data = acceptChannel(remaining);
                                try (HandshakeDeadline timer = new HandshakeDeadline(data, Math.min(3000, remaining))) {
                                    DataByteChannel secure = Pairing.serverData(new DataByteChannel(data), session, i);
                                    connections.add(new TransferConnection(name, secure));
                                    accepted = true;
                                } catch (IOException rejected) {
                                    data.close();
                                    openedChannels.remove(data);
                                }
                            }
                            ctChannel.writeBoolean(true);
                            callback.onAccepted(name);
                        }
                    }
                    // The engine owns bounded heap buffers; no native memory is preallocated here.
                    ctChannel.writeInt(Math.max(1, Math.min(256, remoteBufferCount)));
                    if (!ctChannel.readBoolean()) { callback.onPcOOM(); throw new IOException("Client buffer setup failed"); }
                    ctChannel.writeBoolean(true);
                    remoteFileSystem = ctChannel.readInt();
                    remoteHomeDir = ctChannel.readUTF();
                    callback.onConnectSuccess();
                    startHeartbeat();
                    return;
                }
            } catch (IOException rejected) {
                closeAcceptedChannels();
                buffers.clear();
                ctChannel = null;
                connections = null;
                failures++;
                if (closed) return;
                // A stale/unauthorized client must not terminate the waiting server.
            }
        }
    }

    private void startHeartbeat() {
        synchronized (lifecycleLock) {
            if (closed) return;
            heartbeat = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "HFX-phone-heartbeat");
                thread.setDaemon(true); return thread;
            });
            heartbeat.setRemoveOnCancelPolicy(true);
            heartbeat.scheduleWithFixedDelay(() -> {
                if (closed || !operationLock.tryLock()) return;
                try (HandshakeDeadline timer = new HandshakeDeadline(ctChannel, 5000)) {
                    ctChannel.writeShort(0x7f01);
                    if (!ctChannel.readBoolean()) throw new IOException("Peer disconnected");
                } catch (IOException error) {
                    connectionLost();
                } finally { operationLock.unlock(); }
            }, 3, 3, TimeUnit.SECONDS);
        }
    }

    private void connectionLost() {
        if (closed) return;
        close();
        Runnable listener = disconnectListener;
        if (listener != null) listener.run();
    }

    public void closeServerSocket() { close(); }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        operationLock.lock();
        try {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
        if (!sendFiles(files,localDir,remoteDir,callback)) throw new IOException("Connection interrupted");
            } catch (IOException error) {
            connectionLost();
            throw error;
        } finally { operationLock.unlock(); }
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        operationLock.lock();
        try {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
        ctChannel.writeInt(files.size());
        for (RemoteFile file : files) {
            ctChannel.writeUTF(file.getPath());
        }
        ctChannel.writeUTF(localDir.path);
        ctChannel.writeInt(localDir.fileSystem);
        ctChannel.writeUTF(remoteDir.path);
        if (!receiveFiles(callback)) throw new IOException("Connection interrupted");
            } catch (IOException error) {
            connectionLost();
            throw error;
        } finally { operationLock.unlock(); }
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        operationLock.lock();
        try {
            if (closed || ctChannel == null) throw new IOException("Not connected");
            try (HandshakeDeadline rpcDeadline = new HandshakeDeadline(ctChannel, 30000)) {
                ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
                ctChannel.writeUTF(path);
                int listSize = ctChannel.readInt();
                if (listSize == -1) return null;
                if (listSize < 0 || listSize > 1000000)
                    throw new IOException("Invalid remote directory entry count");
                ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
                for (int i = 0; i < listSize; i++) {
                    remoteFiles.add(new RemoteFile(ctChannel.readUTF(), ctChannel.readUTF(),
                            ctChannel.readLong(), ctChannel.readLong(), ctChannel.readBoolean()));
                }
                return remoteFiles;
            }
        } catch (IOException error) {
            connectionLost();
            throw error;
        } finally { operationLock.unlock(); }
    }

    public boolean deleteRemoteFile(String file) throws IOException {
        operationLock.lock();
        try {
            if (closed || ctChannel == null) throw new IOException("Not connected");
            try (HandshakeDeadline rpcDeadline = new HandshakeDeadline(ctChannel, 30000)) {
                ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
                ctChannel.writeUTF(file);
                return ctChannel.readBoolean();
            }
        } catch (IOException error) {
            connectionLost();
            throw error;
        } finally { operationLock.unlock(); }
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        operationLock.lock();
        try {
            if (closed || ctChannel == null) throw new IOException("Not connected");
            try (HandshakeDeadline rpcDeadline = new HandshakeDeadline(ctChannel, 30000)) {
                ctChannel.writeShort(ControllerIdentifiers.MKDIR);
                ctChannel.writeUTF(parent);
                ctChannel.writeUTF(child);
                return ctChannel.readBoolean();
            }
        } catch (IOException error) {
            connectionLost();
            throw error;
        } finally { operationLock.unlock(); }
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            remoteFiles.addAll(ioService.getAndRemoveFileListSlice(chunkId));
        }
        return remoteFiles;
    }

    public List<String> getConnectionListINames() {
        List<String> names = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            names.add(connection.iName);
        }
        return names;
    }

    public void disconnect() { close(); }

    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            if (heartbeat != null) heartbeat.shutdownNow();
        }
        cancelTransfer();
        ServerSocketChannel listener = serverSocketChannel;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        closeAcceptedChannels();
        buffers.clear();
    }

    @Override protected TransferFileAccess createFileAccess() {
        return new DroidTransferFileAccess(ioService);
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers,dequeCount,ioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(ioService,buffers,files,localDir,remoteDir,operateThreadCount);
    }
}
