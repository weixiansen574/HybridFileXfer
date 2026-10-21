package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** Filesystem operations are performed by the privileged IO process on Android. */
public interface TransferFileAccess {
    default void beginSession() throws IOException {}
    /**
     * Closes client descriptors and retains complete unpublished files. A privileged
     * service keeps a reservation for an in-flight Binder open until its late close
     * acknowledgment arrives, preventing that descriptor from outliving its lock.
     */
    default void endSession() throws IOException {}
    default void cancelPendingOperations() {}
    /** Drains nonfatal filesystem warnings; implementations must bound both count and message size. */
    default List<String> takeWarnings() { return java.util.Collections.emptyList(); }
    final class Handle implements Closeable {
        public final FileChannel channel;
        private final Closeable owner;
        public Handle(FileChannel channel, Closeable owner) { this.channel = channel; this.owner = owner; }
        @Override public void close() throws IOException { owner.close(); }
    }
    final class Prepared {
        public String status = "ready", token = "", target = "", prefixHash = "", message = "";
        public long offset;
        public String[] encode() {
            return new String[]{status, token, target, Long.toString(offset), prefixHash, message};
        }
        public static Prepared decode(String[] a) throws IOException {
            if (a == null || a.length != 6) throw new IOException("Invalid IO service response");
            Prepared p = new Prepared();
            p.status = a[0]; p.token = a[1]; p.target = a[2];
            try { p.offset = Long.parseLong(a[3]); } catch (NumberFormatException e) { throw new IOException(e); }
            p.prefixHash = a[4]; p.message = a[5];
            return p;
        }
    }
    RemoteFile stat(String path) throws IOException;
    String canonicalPath(String path) throws IOException;
    List<RemoteFile> list(String path) throws IOException;
    Handle openRead(String path) throws IOException;
    void createDirectory(String root, String relative, long modified) throws IOException;
    Prepared prepare(String root, String relative, String identity, long size, long modified,
                     String hash, TransferOptions options) throws IOException;
    Handle openStage(String token) throws IOException;
    void checkpoint(String token, long offset) throws IOException;
    /** Durably seals a complete stage and releases its per-file descriptors. */
    void seal(String token) throws IOException;
    /**
     * Opens a sealed file read-only and reserves its generation for verification.
     * Closing the handle closes only the reader. The caller must subsequently call
     * completeVerification or releaseVerification, including on hash failure.
     */
    Handle openSealedRead(String token) throws IOException;
    /** Publishes a reserved generation after trusted local and peer hashes agree. */
    void completeVerification(String token, String expectedSha256, String actualSha256) throws IOException;
    void releaseVerification(String token) throws IOException;
    /** Starts another generation for the same target, preserving the previous complete copy. */
    void resetForRetry(String token) throws IOException;
    /** Publishes complete unchecked data without overwriting an existing destination. */
    String publishUnverified(String token) throws IOException;
    /** Retains complete diagnostic data, closes reservations, and invalidates the token. */
    void retainUnverified(String token) throws IOException;
    void commit(String token, boolean verifySha256) throws IOException;
    void abandon(String token, boolean keep) throws IOException;
}
