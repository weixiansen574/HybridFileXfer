package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** 统一封装本地与 Android 跨进程（Binder）的文件系统读写与暂存提交流程。 */
public interface TransferFileAccess {
    default void beginSession() throws IOException {}
    /**
     * 关闭客户端持有的文件描述符并保留已完整接收的暂存文件。
     * 若存在尚未返回的跨进程打开请求，服务端会保留占坑直到收到对应的关闭确认。
     */
    default void endSession() throws IOException {}
    default void cancelPendingOperations() {}
    /** 取出并清空非致命的文件系统清理提示。 */
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
    /** 封存已完整接收的暂存文件并释放写入句柄，等待后续统一 SHA-256 校验。 */
    void seal(String token) throws IOException;
    /**
     * 以只读方式打开已封存的暂存文件用于计算校验哈希。
     * 调用方在校验结束后必须调用 completeVerification 或 releaseVerification 释放代际锁。
     */
    Handle openSealedRead(String token) throws IOException;
    /** 两端哈希比对一致后，将对应的暂存代际正式发布为目标文件。 */
    void completeVerification(String token, String expectedSha256, String actualSha256) throws IOException;
    void releaseVerification(String token) throws IOException;
    /** 为需要重传的文件开启新的暂存代际，同时保留上一轮已完整接收的旧副本。 */
    void resetForRetry(String token) throws IOException;
    /** 用户提前停止校验时，在不覆盖已有同名文件的前提下保留已完整接收但未完成校验的文件。 */
    String publishUnverified(String token) throws IOException;
    /** 释放校验占坑并失效当前令牌，将哈希不匹配的暂存文件保留在本地供排查。 */
    void retainUnverified(String token) throws IOException;
    void commit(String token, boolean verifySha256) throws IOException;
    void abandon(String token, boolean keep) throws IOException;
}
