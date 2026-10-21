package top.weixiansen574.hybridfilexfer.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TransferHashes {
    private TransferHashes() {}
    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    public static String text(String value) { return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    public static String hash(TransferFileAccess access, String path, long length) throws IOException {
        try (TransferFileAccess.Handle handle = access.openRead(path)) { return hash(handle.channel, length); }
    }
    public static String hash(FileChannel channel, long length) throws IOException {
        return hash(channel,length,() -> false);
    }
    public static String hash(FileChannel channel,long length,java.util.function.BooleanSupplier cancelled) throws IOException {
        if (length < 0) throw new IOException("Negative file length");
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("Cancelled");
        MessageDigest digest = digest();
        ByteBuffer b = ByteBuffer.allocate(128 * 1024);
        long position = 0;
        while (position < length) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IOException("Cancelled");
            b.clear();
            b.limit((int)Math.min(b.capacity(), length - position));
            int n = channel.read(b, position);
            if (n < 0) throw new EOFException("File changed while hashing");
            if (n == 0) { Thread.yield(); continue; }
            position += n;
            b.flip();
            digest.update(b);
        }
        return hex(digest.digest());
    }
}
