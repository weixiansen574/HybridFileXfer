package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.*;
import top.weixiansen574.hybridfilexfer.security.Pairing;
import top.weixiansen574.hybridfilexfer.security.AuthenticationException;
import top.weixiansen574.hybridfilexfer.security.ProtocolMismatchException;
import top.weixiansen574.hybridfilexfer.security.HandshakeDeadline;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXClient extends HFXService implements AutoCloseable {

    protected final String serverControllerAddress;
    protected final int serverPort;
    protected final String homeDir;
    protected volatile boolean isRun = true;
    private volatile boolean closed;
    private String pairingSecret;
    private int controllerPort, adbForwardPort;
    private int connectTimeoutMs = 5000;
    private final List<SocketChannel> openedChannels = Collections.synchronizedList(new ArrayList<SocketChannel>());
    protected ClientCallBack callBack;
    // Native DNS cannot reliably be interrupted. Bound the number of resolver
    // threads, and let callers cancel/time out without waiting for the OS.
    private static final ThreadPoolExecutor RESOLVER = new ThreadPoolExecutor(
            0, 2, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            runnable -> {
                Thread thread = new Thread(runnable, "hfx-dns");
                thread.setDaemon(true);
                return thread;
            });

    public HFXClient(String serverControllerAddress, int serverPort,String homeDir) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
        this.homeDir = homeDir;
        this.controllerPort = serverPort;
    }

    public void configureConnection(String secret, int controllerPort, int adbForwardPort, int timeoutMs) {
        this.pairingSecret = Pairing.validateSecret(secret);
        if (controllerPort < 1 || controllerPort > 65535 || adbForwardPort < 0 || adbForwardPort > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        this.controllerPort = controllerPort;
        this.adbForwardPort = adbForwardPort;
        this.connectTimeoutMs = Math.max(1000, Math.min(30000, timeoutMs));
    }

    private SocketChannel openChannel(InetAddress address, int port, InetAddress bind) throws IOException {
        SocketChannel channel = SocketChannel.open();
        synchronized (openedChannels) {
            if (closed) { channel.close(); throw new IOException("Connection cancelled"); }
            openedChannels.add(channel);
        }
        channel.socket().setTcpNoDelay(true);
        channel.socket().setKeepAlive(true);
        if (bind != null) channel.bind(new InetSocketAddress(bind, 0));
        channel.socket().connect(new InetSocketAddress(address, port), connectTimeoutMs);
        return channel;
    }

    private InetAddress resolveAddress(String host) throws IOException {
        Future<InetAddress> lookup;
        try {
            lookup = RESOLVER.submit(() -> InetAddress.getByName(host));
        } catch (RejectedExecutionException busy) {
            throw new IOException("Address resolver is busy; retry shortly", busy);
        }
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs);
        try {
            while (!closed) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) throw new IOException("Address lookup timed out");
                try {
                    return lookup.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException waiting) {
                    // Recheck close() even when the system DNS implementation is stalled.
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Connection cancelled", cancelled);
                } catch (ExecutionException failed) {
                    throw new IOException("Cannot resolve server address", failed.getCause());
                }
            }
            throw new IOException("Connection cancelled");
        } finally {
            lookup.cancel(true);
        }
    }

    public boolean connect(ConnectServerCallback callback) throws IOException {
        if (pairingSecret == null) throw new AuthenticationException("Enter the pairing code shown on the phone.");
        boolean success = false;
        try {
            callback.onConnectingControlChannel(serverControllerAddress, controllerPort);
            SocketChannel control = openChannel(resolveAddress(serverControllerAddress), controllerPort, null);
            try (HandshakeDeadline deadline = new HandshakeDeadline(control, 60000)) {
                DataByteChannel raw = new DataByteChannel(control);
                raw.write(CLIENT_HEADER.getBytes(StandardCharsets.UTF_8));
                raw.writeInt(VERSION_CODE);
                if (!raw.readBoolean()) {
                    int remoteVersion = raw.readInt();
                    callback.onVersionMismatch(VERSION_CODE, remoteVersion);
                    throw new ProtocolMismatchException(VERSION_CODE, remoteVersion);
                }
                Pairing.Session session = Pairing.client(raw, pairingSecret);
                ctChannel = session.channel;
                int count = ctChannel.readInt();
                if (count < 1 || count > 16) throw new IOException("Invalid channel count");
                String[] names = new String[count];
                InetAddress[] addresses = new InetAddress[count], binds = new InetAddress[count];
                for (int i = 0; i < count; i++) {
                    names[i] = ctChannel.readUTF();
                    if (names[i].length() > 64) throw new IOException("Invalid channel name");
                    int length = ctChannel.readUnsignedByte();
                    if (length != 4 && length != 16) throw new IOException("Invalid IP address");
                    byte[] address = new byte[length];
                    ctChannel.readFully(address);
                    addresses[i] = InetAddress.getByAddress(address);
                    length = ctChannel.readUnsignedByte();
                    if (length != 0 && length != 4 && length != 16) throw new IOException("Invalid bind address");
                    if (length > 0) {
                        address = new byte[length]; ctChannel.readFully(address);
                        binds[i] = InetAddress.getByAddress(address);
                    }
                }
                connections = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    InetAddress address = addresses[i];
                    int port = serverPort;
                    if ("USB_ADB".equals(names[i]) && adbForwardPort > 0) {
                        address = InetAddress.getByName("127.0.0.1");
                        port = adbForwardPort;
                    }
                    callback.onConnectingTransferChannel(names[i], address, binds[i]);
                    SocketChannel data;
                    try {
                        data = openChannel(address, port, binds[i]);
                    } catch (IOException error) {
                        ctChannel.writeBoolean(false);
                        ctChannel.writeUTF(names[i]);
                        callback.onConnectTransferChannelFailed(names[i], address, error);
                        throw error;
                    }
                    // Announce before the data handshake: the server now knows which index to bind.
                    ctChannel.writeBoolean(true);
                    ctChannel.writeUTF(names[i]);
                    try (HandshakeDeadline dataDeadline = new HandshakeDeadline(data, 10000)) {
                        DataByteChannel secure = Pairing.clientData(new DataByteChannel(data), session, i);
                        if (!ctChannel.readBoolean()) throw new IOException("Data channel was rejected");
                        connections.add(new TransferConnection(names[i], secure));
                    }
                }
                int bufferCount = ctChannel.readInt();
                if (bufferCount < 1 || bufferCount > 256) throw new IOException("Invalid buffer count");
                // Protocol 4 allocates bounded per-channel buffers on demand.
                ctChannel.writeBoolean(true);
                if (!ctChannel.readBoolean()) { callback.onRemoteOOM(); return false; }
                ctChannel.writeInt(Directory.getCurrentFileSystem());
                ctChannel.writeUTF(homeDir);
                List<String> channelNames = new ArrayList<>();
                for (TransferConnection channel : connections) channelNames.add(channel.iName);
                callback.onConnectSuccess(channelNames);
                success = true;
                return true;
            }
        } catch (AuthenticationException | ProtocolMismatchException error) {
            throw error;
        } catch (IOException error) {
            callback.onConnectControlFailed();
            throw error;
        } finally {
            if (!success) close();
        }
    }

    @Override public void close() {
        closed = true;
        isRun = false;
        cancelTransfer();
        synchronized (openedChannels) {
            for (SocketChannel channel : openedChannels) {
                try { channel.close(); } catch (IOException ignored) { }
            }
            openedChannels.clear();
        }
        buffers.clear();
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    public void start(ClientCallBack transferFileCallback) throws Exception {
        this.callBack = transferFileCallback;
        //LOOP
        while (isRun) {
            short id = ctChannel.readShort();
            switch (id) {
                case ControllerIdentifiers.LIST_FILES:
                    handleListFiles();
                    break;
                case ControllerIdentifiers.DELETE_FILE:
                    handleDeleteFile();
                    break;
                case ControllerIdentifiers.MKDIR:
                    handleMkdir();
                    break;
                case ControllerIdentifiers.REQUEST_RECEIVE:
                    handleReceiveFiles();
                    break;
                case ControllerIdentifiers.REQUEST_SEND:
                    handleSendFiles();
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    handleShutdown();
                    break;
                case 0x7f01: // Idle heartbeat, serialized with server-initiated operations.
                    ctChannel.writeBoolean(true);
                    break;
                default:
                    throw new IOException("Unknown control command: " + id);
            }
        }
    }


    private void handleDeleteFile() throws Exception {
        ctChannel.writeBoolean(deleteLocalFile(ctChannel.readUTF()));
    }

    protected abstract boolean deleteLocalFile(String path) throws Exception;
    /*public boolean deleteLocalFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }*/

    private void handleMkdir() throws Exception {
        String parent = ctChannel.readUTF();
        String child = ctChannel.readUTF();
        ctChannel.writeBoolean(mkdir(parent, child));
    }

    protected abstract boolean mkdir(String parent, String child) throws Exception;

    private void handleShutdown() {
        close();
        if (callBack != null) callBack.onExit();
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        //System.out.println(path);
        if (!path.equals("/")) {
            List<RemoteFile> files = listFiles(path);
            if (files != null) {
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {
                ctChannel.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            //判断是否是Linux的目录结构，Windows的根目录是C:\\，而不是所有盘符，Linux的根目录是“/”没有盘符概念
            if (roots.length == 1 && roots[0].getAbsolutePath().equals("/")) {
                List<RemoteFile> files = listFiles(roots[0].getPath());
                if (files == null) {
                    ctChannel.writeInt(-1);
                    return;
                }
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {//Windows的
                ctChannel.writeInt(roots.length);
                for (File file : roots) {
                    ctChannel.writeUTF(file.getPath());//不要getName，否则空白
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeLong(file.lastModified());
                    ctChannel.writeLong(file.length());
                    ctChannel.writeBoolean(file.isDirectory());
                }
            }
        }
        //已弃用ObjectOutputStream
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
    }

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    private void writeFile(RemoteFile file) throws IOException {
        ctChannel.writeUTF(file.getName());
        ctChannel.writeUTF(file.getPath());
        ctChannel.writeLong(file.lastModified());
        ctChannel.writeLong(file.getSize());
        ctChannel.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        //System.out.println("准备接收");
        callBack.onReceiving();
        isRun = receiveFiles(callBack);
    }

    private void handleSendFiles() throws IOException {
        int listSize = ctChannel.readInt();
        if (listSize < 0 || listSize > 100000) throw new IOException("Invalid file list size");
        List<RemoteFile> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new RemoteFile(new File(ctChannel.readUTF())));
        }
        Directory remoteDir = new Directory(ctChannel.readUTF(), ctChannel.readInt());//对方的localDir
        Directory localDir = new Directory(ctChannel.readUTF(), Directory.getCurrentFileSystem());//对方为remoteDir
        callBack.onSending();
        isRun = sendFiles(fileList,localDir,remoteDir,callBack);
    }

    protected void freeBuffers(){
        buffers.clear();
    }

}
