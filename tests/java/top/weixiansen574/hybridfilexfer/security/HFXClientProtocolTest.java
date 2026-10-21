package top.weixiansen574.hybridfilexfer.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;
import top.weixiansen574.nio.DataByteChannel;

/**
 * Exercises the real desktop client's connect/start path against a small protocol
 * peer. Every socket is loopback-only; no Android SDK, ADB, or device is used.
 */
public final class HFXClientProtocolTest {
    private static final String SECRET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final AtomicInteger CHECKS = new AtomicInteger();

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    private static void waitFor(CountDownLatch latch, String message) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hfx-protocol-test");
            thread.setDaemon(true);
            return thread;
        });
        final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        final ServerSocketChannel control = listen();
        final ServerSocketChannel network = listen();
        final ServerSocketChannel adb = listen();

        Fixture() throws IOException { }

        ServerSocketChannel listen() throws IOException {
            ServerSocketChannel listener = ServerSocketChannel.open();
            resources.add(listener);
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            return listener;
        }

        SocketChannel track(SocketChannel socket) throws IOException {
            resources.add(socket);
            socket.socket().setTcpNoDelay(true);
            resources.add(new HandshakeDeadline(socket, 10000));
            return socket;
        }

        SocketChannel accept(ServerSocketChannel listener) throws IOException {
            return track(listener.accept());
        }

        int port(ServerSocketChannel listener) throws IOException {
            return ((InetSocketAddress) listener.getLocalAddress()).getPort();
        }

        ProbeClient client(String directory) throws IOException {
            ProbeClient client = new ProbeClient(port(network), directory);
            resources.add(client);
            client.configureConnection(SECRET, port(control), port(adb), 2000);
            return client;
        }

        <T> Future<T> submit(Callable<T> work) { return executor.submit(work); }

        @Override public void close() throws Exception {
            for (AutoCloseable resource : resources) {
                try { resource.close(); } catch (Exception ignored) { }
            }
            executor.shutdownNow();
            check(executor.awaitTermination(3, TimeUnit.SECONDS),
                    "Protocol fixture releases its socket workers");
        }
    }

    private static final class ProbeClient extends JdkHFXClient {
        ProbeClient(int dataPort, String directory) {
            super("127.0.0.1", dataPort, directory);
        }
        DataByteChannel data(int index) { return connections.get(index).channel; }
    }

    private static final class Events implements ConnectServerCallback, ClientCallBack {
        volatile int localVersion = -1, remoteVersion = -1, controlPort;
        volatile int successes, failures, exits;
        final List<String> connected = new ArrayList<>();
        final List<InetAddress> addresses = new ArrayList<>();
        @Override public void onConnectingControlChannel(String address, int port) { controlPort = port; }
        @Override public void onVersionMismatch(int local, int remote) {
            localVersion = local; remoteVersion = remote;
        }
        @Override public void onConnectControlFailed() { failures++; }
        @Override public void onConnectingTransferChannel(String name, InetAddress address, InetAddress bind) {
            addresses.add(address);
        }
        @Override public void onConnectTransferChannelFailed(String name, InetAddress address, Exception error) { }
        @Override public void onOOM(int created, int required, long maximum, String architecture) { }
        @Override public void onRemoteOOM() { }
        @Override public void onConnectSuccess(List<String> names) { successes++; connected.addAll(names); }
        @Override public void onReceiving() { throw new AssertionError("Unexpected transfer"); }
        @Override public void onSending() { throw new AssertionError("Unexpected transfer"); }
        @Override public void onExit() { exits++; }
        @Override public void onFileUploading(String name, String path, long current, long total) { }
        @Override public void onFileDownloading(String name, String path, long current, long total) { }
        @Override public void onSpeedInfo(List<TrafficInfo> infos) { }
        @Override public void onChannelComplete(String name, long bytes, long elapsed) { }
        @Override public void onChannelError(String name, int type, String message) { }
        @Override public void onReadFileError(String message) { }
        @Override public void onWriteFileError(String message) { }
        @Override public void onComplete(boolean upload, long bytes, long elapsed) { }
        @Override public void onIncomplete() { }
    }

    private static DataByteChannel readHeader(SocketChannel socket) throws IOException {
        DataByteChannel raw = new DataByteChannel(socket);
        byte[] header = new byte[4];
        raw.readFully(header);
        check(HFXService.CLIENT_HEADER.equals(new String(header, StandardCharsets.US_ASCII)),
                "Actual client emits the control header");
        check(raw.readInt() == HFXService.VERSION_CODE, "Actual client sends current protocol version");
        return raw;
    }

    private static Pairing.Session pairControl(SocketChannel socket) throws IOException {
        DataByteChannel raw = readHeader(socket);
        raw.writeBoolean(true);
        return Pairing.server(raw, SECRET);
    }

    private static void advertise(DataByteChannel control, String... names) throws IOException {
        control.writeInt(names.length);
        for (String name : names) {
            control.writeUTF(name);
            control.writeByte(4);
            // An unreachable advertised USB address proves the client uses its
            // independently configured local ADB forwarding endpoint.
            control.write("USB_ADB".equals(name)
                    ? new byte[] {(byte) 203, 0, 113, 1} : new byte[] {127, 0, 0, 1});
            control.writeByte(0);
        }
    }

    private static void announced(DataByteChannel control, String name) throws IOException {
        check(control.readBoolean(), "Client announces a connected data socket before authenticating it");
        check(name.equals(control.readUTF()), "Data socket announcement preserves channel order");
    }

    private static void finishNegotiation(DataByteChannel control, String directory) throws IOException {
        control.writeInt(2);
        check(control.readBoolean(), "Client accepts bounded buffer allocation");
        control.writeBoolean(true);
        check(control.readInt() == Directory.getCurrentFileSystem(), "Filesystem negotiation");
        check(directory.equals(control.readUTF()), "Home directory survives encrypted UTF negotiation");
    }

    private static void peerClosed(SocketChannel socket, String message) throws IOException {
        try {
            check(socket.read(ByteBuffer.allocate(1)) == -1, message);
        } catch (SocketException reset) {
            // Windows can report RST instead of EOF when the client closes a
            // data socket. A locally expired deadline must still fail the test.
            check(socket.isOpen(), message + " (peer reset, not local deadline)");
        }
    }

    private static void versionMismatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProbeClient client = fixture.client(".");
            Events events = new Events();
            Future<Void> server = fixture.submit(() -> {
                SocketChannel socket = fixture.accept(fixture.control);
                DataByteChannel raw = readHeader(socket);
                raw.writeBoolean(false);
                raw.writeInt(300);
                peerClosed(socket, "Rejected version closes the control socket");
                return null;
            });
            Future<Void> connecting = fixture.submit(() -> {
                try {
                    client.connect(events);
                    throw new AssertionError("Client accepted an incompatible version");
                } catch (ProtocolMismatchException expected) {
                    check(expected.getMessage().contains("300"), "Version error identifies the remote protocol");
                }
                return null;
            });
            await(connecting); await(server);
            check(events.localVersion == HFXService.VERSION_CODE && events.remoteVersion == 300,
                    "Version callback preserves both versions");
            check(events.successes == 0 && events.failures == 0,
                    "Incompatible version is distinct from transient connection failure");
        }
    }

    private static void wrongPairingCode() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProbeClient client = fixture.client(".");
            Events events = new Events();
            client.configureConnection("ZZZZZZZZZZZZZZZZZZZZ", fixture.port(fixture.control), 0, 2000);
            Future<Void> server = fixture.submit(() -> {
                SocketChannel socket = fixture.accept(fixture.control);
                DataByteChannel raw = readHeader(socket);
                raw.writeBoolean(true);
                try {
                    Pairing.server(raw, SECRET);
                    throw new AssertionError("Server accepted a wrong pairing code");
                } catch (AuthenticationException expected) { }
                peerClosed(socket, "Failed pairing closes the actual client's socket");
                return null;
            });
            Future<Void> connecting = fixture.submit(() -> {
                try {
                    client.connect(events);
                    throw new AssertionError("Client accepted wrong pairing credentials");
                } catch (AuthenticationException expected) { }
                return null;
            });
            await(connecting); await(server);
            check(events.successes == 0 && events.failures == 0,
                    "Authentication failure is distinct from retryable transport failure");
        }
    }

    private static void completeSession() throws Exception {
        Path directory = Files.createTempDirectory("hfx-client-protocol-");
        Path file = directory.resolve("example-\u6587\u4ef6.txt");
        byte[] contents = "loopback test".getBytes(StandardCharsets.UTF_8);
        Files.write(file, contents);
        try (Fixture fixture = new Fixture()) {
            ProbeClient client = fixture.client(directory.toString());
            Events events = new Events();
            Future<Void> server = fixture.submit(() -> {
                SocketChannel controlSocket = fixture.accept(fixture.control);
                Pairing.Session session = pairControl(controlSocket);
                DataByteChannel control = session.channel;
                advertise(control, "NETWORK", "USB_ADB");
                List<SocketChannel> sockets = new ArrayList<>();
                List<DataByteChannel> channels = new ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    announced(control, index == 0 ? "NETWORK" : "USB_ADB");
                    SocketChannel data = fixture.accept(index == 0 ? fixture.network : fixture.adb);
                    sockets.add(data);
                    channels.add(Pairing.serverData(new DataByteChannel(data), session, index));
                    control.writeBoolean(true);
                }
                finishNegotiation(control, directory.toString());
                for (int index = 0; index < 2; index++) {
                    channels.get(index).writeUTF("encrypted-data-" + index);
                    check(channels.get(index).readLong() == 0x123456789abcdef0L + index,
                            "Each negotiated data channel carries an authenticated reply");
                }
                for (int heartbeat = 0; heartbeat < 2; heartbeat++) {
                    control.writeShort(0x7f01);
                    check(control.readBoolean(), "Actual start loop acknowledges idle heartbeat");
                }
                control.writeShort(ControllerIdentifiers.LIST_FILES);
                control.writeUTF(directory.toString());
                check(control.readInt() == 1, "Control loop handles a directory request after heartbeat");
                check(file.getFileName().toString().equals(control.readUTF()), "Unicode listing name");
                check(file.toString().equals(control.readUTF()), "Listing returns the requested file path");
                check(control.readLong() > 0, "Listing includes modification time");
                check(control.readLong() == contents.length, "Listing includes size");
                check(!control.readBoolean(), "Listing identifies an ordinary file");
                control.writeShort(ControllerIdentifiers.SHUTDOWN);
                peerClosed(controlSocket, "Remote SHUTDOWN closes the control socket");
                for (SocketChannel socket : sockets) peerClosed(socket, "Remote SHUTDOWN closes every data socket");
                return null;
            });
            Future<Void> run = fixture.submit(() -> {
                check(client.connect(events), "Real client completes control and data negotiation");
                for (int index = 0; index < 2; index++) {
                    check(("encrypted-data-" + index).equals(client.data(index).readUTF()),
                            "Negotiated data channel decrypts server records");
                    client.data(index).writeLong(0x123456789abcdef0L + index);
                }
                client.start(events);
                return null;
            });
            await(run); await(server);
            check(events.controlPort == fixture.port(fixture.control),
                    "Control port is independent of the network data port");
            check(events.connected.equals(Arrays.asList("NETWORK", "USB_ADB")), "Both channel names reach UI callback");
            check(events.addresses.size() == 2 && events.addresses.get(1).isLoopbackAddress(),
                    "USB data uses the random local forwarding port and loopback address");
            check(events.successes == 1 && events.exits == 1 && events.failures == 0,
                    "Successful connection and remote exit callbacks occur exactly once");
            client.close();
            check(events.exits == 1, "Repeated close is idempotent");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static Pairing.Session unrelatedSession(Fixture fixture) throws Exception {
        ServerSocketChannel listener = fixture.listen();
        SocketChannel client = fixture.track(SocketChannel.open(listener.getLocalAddress()));
        SocketChannel server = fixture.accept(listener);
        Future<Pairing.Session> pairing = fixture.submit(() ->
                Pairing.client(new DataByteChannel(client), SECRET));
        Pairing.Session unrelated = Pairing.server(new DataByteChannel(server), SECRET);
        await(pairing);
        client.close(); server.close(); listener.close();
        return unrelated;
    }

    private static void wrongDataBinding(boolean wrongSession) throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProbeClient client = fixture.client(".");
            Events events = new Events();
            Pairing.Session unrelated = wrongSession ? unrelatedSession(fixture) : null;
            Future<Void> server = fixture.submit(() -> {
                SocketChannel controlSocket = fixture.accept(fixture.control);
                Pairing.Session session = pairControl(controlSocket);
                advertise(session.channel, "NETWORK");
                announced(session.channel, "NETWORK");
                SocketChannel data = fixture.accept(fixture.network);
                try {
                    Pairing.serverData(new DataByteChannel(data),
                            wrongSession ? unrelated : session, wrongSession ? 0 : 1);
                    throw new AssertionError("Data socket was accepted with the wrong binding");
                } catch (AuthenticationException expected) {
                    data.close();
                }
                peerClosed(controlSocket, "Failed data binding closes the control connection");
                return null;
            });
            Future<Void> connecting = fixture.submit(() -> {
                try {
                    client.connect(events);
                    throw new AssertionError("Client connected despite rejected data authentication");
                } catch (IOException expected) { }
                return null;
            });
            await(connecting); await(server);
            check(events.successes == 0, wrongSession
                    ? "A different session using the same code cannot authenticate this client's data"
                    : "A different channel index cannot authenticate this client's data");
        }
    }

    private static void closeWhileBlocked(final int stage) throws Exception {
        // Stage 0: pairing nonce; stage 1: data proof; stage 2: idle command loop.
        try (Fixture fixture = new Fixture()) {
            ProbeClient client = fixture.client(".");
            Events events = new Events();
            CountDownLatch blocked = new CountDownLatch(1);
            Future<Void> server = fixture.submit(() -> {
                SocketChannel controlSocket = fixture.accept(fixture.control);
                DataByteChannel raw = readHeader(controlSocket);
                raw.writeBoolean(true);
                SocketChannel dataSocket = null;
                if (stage > 0) {
                    Pairing.Session session = Pairing.server(raw, SECRET);
                    advertise(session.channel, "NETWORK");
                    announced(session.channel, "NETWORK");
                    dataSocket = fixture.accept(fixture.network);
                    if (stage == 1) {
                        // Read the complete client proof but deliberately withhold the peer proof.
                        new DataByteChannel(dataSocket).readFully(new byte[72]);
                    } else {
                        Pairing.serverData(new DataByteChannel(dataSocket), session, 0);
                        session.channel.writeBoolean(true);
                        finishNegotiation(session.channel, ".");
                        session.channel.writeShort(0x7f01);
                        check(session.channel.readBoolean(), "Heartbeat establishes that start is running");
                    }
                }
                blocked.countDown();
                peerClosed(controlSocket, "Local close releases control socket at stage " + stage);
                if (dataSocket != null) peerClosed(dataSocket, "Local close releases data socket at stage " + stage);
                return null;
            });
            Future<Void> run = fixture.submit(() -> {
                try {
                    if (client.connect(events)) client.start(events);
                    check(stage == 2, "Cancelled negotiation cannot report connected");
                } catch (IOException expected) { }
                return null;
            });
            waitFor(blocked, "Peer reaches blocking stage " + stage);
            long start = System.nanoTime();
            client.close();
            await(run); await(server);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000,
                    "Close interrupts blocked protocol I/O within 3 seconds at stage " + stage);
            check(events.successes == (stage == 2 ? 1 : 0), "Only a fully negotiated connection reports success");
            check(events.exits == 0, "Local close does not report a remote shutdown");
        }
    }

    public static void main(String[] args) throws Exception {
        versionMismatch();
        wrongPairingCode();
        completeSession();
        wrongDataBinding(false);
        wrongDataBinding(true);
        closeWhileBlocked(0);
        closeWhileBlocked(1);
        closeWhileBlocked(2);
        System.out.println("HFXClientProtocolTest: " + CHECKS.get()
                + " checks passed (real connect/start, pairing, data binding, endpoints, heartbeat, close)");
    }
}
