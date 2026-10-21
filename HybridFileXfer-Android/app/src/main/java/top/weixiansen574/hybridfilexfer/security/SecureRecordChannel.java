package top.weixiansen574.hybridfilexfer.security;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded AES-GCM records with independent directional keys and implicit sequence
 * numbers. Authenticating the handshake alone would not protect later commands.
 */
public final class SecureRecordChannel implements ByteChannel {
    private static final int MAX_PLAIN = 64 * 1024;
    private final ByteChannel raw;
    private final byte[] sendKey, receiveKey, sendSalt, receiveSalt;
    private final Object readLock = new Object(), writeLock = new Object();
    private long sendSequence, receiveSequence;
    private ByteBuffer pending = ByteBuffer.allocate(0);

    public SecureRecordChannel(ByteChannel raw, byte[] sendKey, byte[] receiveKey,
                               byte[] sendSalt, byte[] receiveSalt) {
        this.raw = raw;
        this.sendKey = sendKey.clone();
        this.receiveKey = receiveKey.clone();
        this.sendSalt = sendSalt.clone();
        this.receiveSalt = receiveSalt.clone();
    }

    private byte[] crypt(int mode, byte[] key, byte[] salt, long sequence, byte[] input)
            throws IOException {
        if (sequence < 0) throw new IOException("Encrypted session record limit reached");
        try {
            byte[] nonce = ByteBuffer.allocate(12).put(salt, 0, 4).putLong(sequence).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ByteBuffer.allocate(12).putInt(400).putLong(sequence).array());
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            try { close(); } catch (IOException ignored) { }
            throw new IOException("Encrypted session integrity check failed", e);
        }
    }

    private void readFully(ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (raw.read(target) < 0) throw new EOFException("Incomplete encrypted record");
        }
    }

    private void writeFully(ByteBuffer source) throws IOException {
        while (source.hasRemaining()) raw.write(source);
    }

    @Override public int read(ByteBuffer destination) throws IOException {
        if (!destination.hasRemaining()) return 0;
        synchronized (readLock) {
            if (!pending.hasRemaining()) {
                ByteBuffer length = ByteBuffer.allocate(4);
                int first = raw.read(length);
                if (first < 0) return -1;
                readFully(length);
                length.flip();
                int count = length.getInt();
                if (count < 17 || count > MAX_PLAIN + 16) {
                    close();
                    throw new IOException("Invalid encrypted record length");
                }
                byte[] ciphertext = new byte[count];
                readFully(ByteBuffer.wrap(ciphertext));
                pending = ByteBuffer.wrap(crypt(Cipher.DECRYPT_MODE, receiveKey,
                        receiveSalt, receiveSequence++, ciphertext));
            }
            int count = Math.min(destination.remaining(), pending.remaining());
            ByteBuffer slice = pending.slice();
            slice.limit(count);
            destination.put(slice);
            pending.position(pending.position() + count);
            return count;
        }
    }

    @Override public int write(ByteBuffer source) throws IOException {
        if (!source.hasRemaining()) return 0;
        synchronized (writeLock) {
            int count = Math.min(source.remaining(), MAX_PLAIN);
            byte[] plain = new byte[count];
            source.get(plain);
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, sendKey, sendSalt,
                    sendSequence++, plain);
            ByteBuffer record = ByteBuffer.allocate(4 + encrypted.length);
            record.putInt(encrypted.length).put(encrypted).flip();
            writeFully(record);
            return count;
        }
    }

    @Override public boolean isOpen() { return raw.isOpen(); }
    @Override public void close() throws IOException { raw.close(); }
}
