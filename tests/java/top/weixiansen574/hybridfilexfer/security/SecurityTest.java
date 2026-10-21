package top.weixiansen574.hybridfilexfer.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.*;
import top.weixiansen574.nio.DataByteChannel;

/** Actual loopback protocol checks plus deterministic corruption/short-I/O tests. */
public final class SecurityTest {
    private static final String SECRET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static int assertions;
    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }

    private static final class Pair implements AutoCloseable {
        final SocketChannel client, server;
        Pair() throws IOException {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                client = SocketChannel.open(listener.getLocalAddress());
                server = listener.accept();
            }
        }
        @Override public void close() throws IOException { client.close(); server.close(); }
    }

    private static void pairedRoundTrip() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Pair sockets = new Pair()) {
            Future<Pairing.Session> remote = executor.submit(() ->
                    Pairing.server(new DataByteChannel(sockets.server), SECRET));
            Pairing.Session client = Pairing.client(new DataByteChannel(sockets.client), SECRET);
            Pairing.Session server = remote.get(5, TimeUnit.SECONDS);
            byte[] payload = new byte[256 * 1024 + 731];
            new java.util.Random(123).nextBytes(payload);
            Future<byte[]> read = executor.submit(() -> {
                byte[] bytes = new byte[payload.length];
                server.channel.readFully(bytes);
                server.channel.writeUTF("verified");
                return bytes;
            });
            client.channel.write(payload);
            check("verified".equals(client.channel.readUTF()), "Authenticated reply");
            check(Arrays.equals(payload, read.get(5, TimeUnit.SECONDS)), "Multi-record payload");
            try (Pair data = new Pair()) {
                Future<DataByteChannel> accepted = executor.submit(() ->
                        Pairing.serverData(new DataByteChannel(data.server), server, 2));
                DataByteChannel sending = Pairing.clientData(new DataByteChannel(data.client), client, 2);
                DataByteChannel receiving = accepted.get(5, TimeUnit.SECONDS);
                sending.writeLong(0x123456789abcdef0L);
                check(receiving.readLong() == 0x123456789abcdef0L, "Bound data channel");
            }
            try (Pair data = new Pair()) {
                Future<Boolean> rejected = executor.submit(() -> {
                    try { Pairing.serverData(new DataByteChannel(data.server), server, 3); return false; }
                    catch (AuthenticationException expected) { data.server.close(); return true; }
                });
                try {
                    Pairing.clientData(new DataByteChannel(data.client), client, 2);
                    throw new AssertionError("Wrong data index accepted");
                } catch (IOException expected) { }
                check(rejected.get(5, TimeUnit.SECONDS), "Wrong channel index rejected");
            }
        } finally { executor.shutdownNow(); }
    }

    private static void wrongCode() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Pair sockets = new Pair()) {
            Future<Boolean> remote = executor.submit(() -> {
                try { Pairing.server(new DataByteChannel(sockets.server), SECRET); return false; }
                catch (AuthenticationException expected) { return true; }
            });
            try {
                Pairing.client(new DataByteChannel(sockets.client), "ZZZZZZZZZZZZZZZZZZZZ");
                throw new AssertionError("Wrong pairing code accepted");
            } catch (AuthenticationException expected) { assertions++; }
            check(remote.get(5, TimeUnit.SECONDS), "Server rejects wrong code");
        } finally { executor.shutdownNow(); }
    }

    private static final class MemoryWire implements ByteChannel {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] input;
        int offset;
        boolean open = true;
        @Override public int write(ByteBuffer source) {
            int count = Math.min(7, source.remaining());
            byte[] chunk = new byte[count]; source.get(chunk);
            output.write(chunk, 0, chunk.length); return count;
        }
        @Override public int read(ByteBuffer target) {
            if (input == null || offset == input.length) return -1;
            int count = Math.min(5, Math.min(target.remaining(), input.length - offset));
            target.put(input, offset, count); offset += count; return count;
        }
        void rewind() { input = output.toByteArray(); offset = 0; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }

    private static void corruptionAndFragmentation() throws Exception {
        byte[] key = new byte[16], salt = new byte[4];
        Arrays.fill(key, (byte) 23);
        MemoryWire wire = new MemoryWire();
        DataByteChannel output = new DataByteChannel(new SecureRecordChannel(wire, key, key, salt, salt));
        output.writeInt(123456);
        output.writeUTF("short writes \u4e2d\u6587");
        wire.rewind();
        DataByteChannel input = new DataByteChannel(new SecureRecordChannel(wire, key, key, salt, salt));
        check(input.readInt() == 123456, "Short writes preserve integer");
        check("short writes \u4e2d\u6587".equals(input.readUTF()), "Fragmented encrypted UTF");
        wire.rewind();
        wire.input[wire.input.length - 1] ^= 1;
        input = new DataByteChannel(new SecureRecordChannel(wire, key, key, salt, salt));
        input.readInt();
        try {
            input.readUTF();
            throw new AssertionError("Corrupt record accepted");
        } catch (IOException expected) { check(!wire.isOpen(), "Tamper closes connection"); }
        MemoryWire malformed = new MemoryWire();
        malformed.input = ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array();
        input = new DataByteChannel(new SecureRecordChannel(malformed, key, key, salt, salt));
        try { input.readInt(); throw new AssertionError("Unbounded record accepted"); }
        catch (IOException expected) { assertions++; }
        MemoryWire plainWire = new MemoryWire();
        DataByteChannel plain = new DataByteChannel(plainWire);
        byte[] many = new byte[4096];
        Arrays.fill(many, (byte) 91);
        plain.write(many);
        check(plainWire.output.size() == many.length, "DataOutput completes partial writes");
    }

    private static void stalledPeerDeadline() throws Exception {
        try (Pair sockets = new Pair();
             HandshakeDeadline timer = new HandshakeDeadline(sockets.client, 100)) {
            long start = System.nanoTime();
            try {
                new DataByteChannel(sockets.client).readInt();
                throw new AssertionError("Stalled peer did not close");
            } catch (IOException expected) {
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000,
                        "SocketChannel handshake deadline is enforced");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(Pairing.newSecret().length() == 20, "Random pairing code");
        try { Pairing.validateSecret("1234"); throw new AssertionError("Short code accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        pairedRoundTrip();
        wrongCode();
        corruptionAndFragmentation();
        stalledPeerDeadline();
        System.out.println("SecurityTest: " + assertions + " checks passed");
    }
}
