package top.weixiansen574.hybridfilexfer.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import top.weixiansen574.nio.DataByteChannel;

/** Mutual pairing proof, channel binding and per-connection record keys. */
public final class Pairing {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private Pairing() { }

    public static String newSecret() {
        StringBuilder code = new StringBuilder(20);
        for (int i = 0; i < 20; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }

    public static String validateSecret(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{12,128}"))
            throw new IllegalArgumentException("Enter the complete pairing code shown on the phone.");
        return code;
    }

    public static final class Session {
        public final DataByteChannel channel;
        private final byte[] key;
        private Session(DataByteChannel channel, byte[] key) {
            this.channel = channel;
            this.key = key;
        }
    }

    private static byte[] random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] hmac(byte[] key, String purpose, byte[]... fields) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(("HybridFileXfer/400/" + purpose + "\0").getBytes(StandardCharsets.UTF_8));
            for (byte[] field : fields) mac.update(field);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException("Secure pairing is unavailable", e);
        }
    }

    private static DataByteChannel protect(DataByteChannel raw, byte[] key, boolean client)
            throws IOException {
        byte[] c2s = hmac(key, "client-record-key");
        byte[] s2c = hmac(key, "server-record-key");
        byte[] cn = hmac(key, "client-record-nonce");
        byte[] sn = hmac(key, "server-record-nonce");
        return new DataByteChannel(new SecureRecordChannel(raw,
                Arrays.copyOf(client ? c2s : s2c, 16),
                Arrays.copyOf(client ? s2c : c2s, 16),
                Arrays.copyOf(client ? cn : sn, 4),
                Arrays.copyOf(client ? sn : cn, 4)));
    }

    public static Session client(DataByteChannel raw, String secret) throws IOException {
        byte[] key = validateSecret(secret).getBytes(StandardCharsets.US_ASCII);
        byte[] serverNonce = new byte[32], clientNonce = random();
        raw.readFully(serverNonce);
        raw.write(clientNonce);
        raw.write(hmac(key, "client-proof", serverNonce, clientNonce));
        if (!raw.readBoolean()) throw new AuthenticationException("Pairing code was rejected.");
        byte[] proof = new byte[32];
        raw.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(key, "server-proof", serverNonce, clientNonce)))
            throw new AuthenticationException("The remote device could not prove its identity.");
        byte[] master = hmac(key, "session-key", serverNonce, clientNonce);
        return new Session(protect(raw, master, true), master);
    }

    public static Session server(DataByteChannel raw, String secret) throws IOException {
        byte[] key = validateSecret(secret).getBytes(StandardCharsets.US_ASCII);
        byte[] serverNonce = random(), clientNonce = new byte[32], proof = new byte[32];
        raw.write(serverNonce);
        raw.readFully(clientNonce);
        raw.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(key, "client-proof", serverNonce, clientNonce))) {
            raw.writeBoolean(false);
            throw new AuthenticationException("Pairing code was rejected.");
        }
        raw.writeBoolean(true);
        raw.write(hmac(key, "server-proof", serverNonce, clientNonce));
        byte[] master = hmac(key, "session-key", serverNonce, clientNonce);
        return new Session(protect(raw, master, false), master);
    }

    public static DataByteChannel clientData(DataByteChannel raw, Session session, int index)
            throws IOException {
        raw.writeInt(0x48465844); // HFXD
        raw.writeInt(index);
        byte[] nonce = random();
        byte[] number = java.nio.ByteBuffer.allocate(4).putInt(index).array();
        raw.write(nonce);
        raw.write(hmac(session.key, "data-client", number, nonce));
        byte[] serverNonce = new byte[32], proof = new byte[32];
        raw.readFully(serverNonce);
        raw.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(session.key, "data-server", number, nonce, serverNonce)))
            throw new AuthenticationException("Data channel authentication failed.");
        byte[] key = hmac(session.key, "data-key", number, nonce, serverNonce);
        return protect(raw, key, true);
    }

    public static DataByteChannel serverData(DataByteChannel raw, Session session, int index)
            throws IOException {
        if (raw.readInt() != 0x48465844 || raw.readInt() != index)
            throw new AuthenticationException("Unexpected data channel.");
        byte[] number = java.nio.ByteBuffer.allocate(4).putInt(index).array();
        byte[] nonce = new byte[32], proof = new byte[32], serverNonce = random();
        raw.readFully(nonce);
        raw.readFully(proof);
        if (!MessageDigest.isEqual(proof, hmac(session.key, "data-client", number, nonce)))
            throw new AuthenticationException("Unpaired data channel.");
        raw.write(serverNonce);
        raw.write(hmac(session.key, "data-server", number, nonce, serverNonce));
        byte[] key = hmac(session.key, "data-key", number, nonce, serverNonce);
        return protect(raw, key, false);
    }
}
