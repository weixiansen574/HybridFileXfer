import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import top.weixiansen574.hybridfilexfer.core.*;
import top.weixiansen574.hybridfilexfer.core.bean.*;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkTransferFileAccess;
import top.weixiansen574.nio.DataByteChannel;

/** Synthetic file protection checks, including post-publication cleanup failure. */
public final class TransferProtectionTest {
    private static final byte[] NEW_BYTES = new byte[] {7, 11, 19, 23, 29};
    private static final byte[] OLD_BYTES = new byte[] {2, 3, 5};
    private static final String IDENTITY = TransferHashes.text("protection-source");
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static TransferOptions options(TransferOptions.ConflictPolicy policy) {
        TransferOptions options = new TransferOptions();
        options.conflictPolicy = policy;
        options.timeoutSeconds = 5;
        return options;
    }

    private static String digest(byte[] bytes) {
        java.security.MessageDigest digest = TransferHashes.digest();
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private static TransferFileAccess.Prepared prepare(JdkTransferFileAccess fs, Path root,
                                                       TransferOptions options) throws IOException {
        return fs.prepare(root.toString(), "file.bin", IDENTITY, NEW_BYTES.length, 1000,
                digest(NEW_BYTES), options);
    }

    private static void fill(JdkTransferFileAccess fs, TransferFileAccess.Prepared prepared) throws IOException {
        try (TransferFileAccess.Handle output = fs.openStage(prepared.token)) {
            ByteBuffer data = ByteBuffer.wrap(NEW_BYTES);
            while (data.hasRemaining()) output.channel.write(data);
            output.channel.force(true);
        }
        fs.checkpoint(prepared.token, NEW_BYTES.length);
    }

    private static Path metadata(Path part) {
        String name = part.getFileName().toString();
        return part.resolveSibling(name.substring(0, name.length() - ".part".length()) + ".meta");
    }

    private static void cleanup(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
    }

    private static void destinationAppearedDuringTransfer() throws Exception {
        Path root = Files.createTempDirectory("hfx-publish-race-");
        JdkTransferFileAccess fs = new JdkTransferFileAccess();
        TransferFileAccess.Prepared prepared = null;
        try {
            prepared = prepare(fs, root, options(TransferOptions.ConflictPolicy.KEEP_BOTH));
            fill(fs, prepared);
            Files.write(root.resolve("file.bin"), OLD_BYTES);
            boolean rejected = false;
            try { fs.commit(prepared.token, true); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "KEEP_BOTH rejects a destination that appeared after prepare");
            check(Arrays.equals(OLD_BYTES, Files.readAllBytes(root.resolve("file.bin"))),
                    "A concurrent destination is preserved byte for byte");
            check(Files.exists(Paths.get(fs.stagingPath(prepared.token))),
                    "Failed publication preserves the complete staging file");
        } finally {
            if (prepared != null) fs.abandon(prepared.token, false);
            cleanup(root);
        }
    }

    private static final class CleanupFaultFiles extends JdkTransferFileAccess {
        @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
            super.atomicMove(source, target, replace);
            if (source.getName().endsWith(".part")) {
                // Deterministic filesystem fault: after the real publication,
                // replace metadata with a nonempty directory so deletion fails
                // on every platform. Its unknown contents must be preserved.
                Path meta = metadata(source.toPath());
                Files.deleteIfExists(meta);
                Files.createDirectory(meta);
                Files.write(meta.resolve("held-by-another-process"), OLD_BYTES);
            }
        }
    }

    private static void postPublicationCleanupFailure() throws Exception {
        Path root = Files.createTempDirectory("hfx-cleanup-failure-");
        JdkTransferFileAccess fs = new CleanupFaultFiles();
        TransferFileAccess.Prepared prepared = null;
        try {
            Files.write(root.resolve("file.bin"), OLD_BYTES);
            prepared = prepare(fs, root, options(TransferOptions.ConflictPolicy.OVERWRITE));
            Path part = Paths.get(fs.stagingPath(prepared.token));
            fill(fs, prepared);
            fs.commit(prepared.token, true);
            check(Arrays.equals(NEW_BYTES, Files.readAllBytes(root.resolve("file.bin"))),
                    "Cleanup failure does not turn an already published file into a failed transfer");
            check(!Files.exists(part), "Published staging file has moved to its final name");
            check(Files.exists(metadata(part).resolve("held-by-another-process")),
                    "Cleanup never removes unfamiliar metadata contents");
            check(fs.takeWarnings().size() == 1 && fs.takeWarnings().isEmpty(),
                    "Cleanup failure is a nonfatal warning that drains exactly once");
        } finally {
            if (prepared != null) fs.abandon(prepared.token, true);
            cleanup(root);
        }
    }

    private static void windowsMetadataHandleAndNextTransfer() throws Exception {
        if (File.separatorChar != '\\') {
            System.out.println("SKIP Windows metadata sharing-mode check on this platform");
            return;
        }
        OpenOption noShareDelete = null;
        for (Object constant : Class.forName("com.sun.nio.file.ExtendedOpenOption").getEnumConstants()) {
            if ("NOSHARE_DELETE".equals(constant.toString())) noShareDelete = (OpenOption) constant;
        }
        check(noShareDelete != null, "JDK exposes the Windows no-delete-sharing option");
        Path root = Files.createTempDirectory("hfx-meta-sharing-");
        JdkTransferFileAccess fs = new JdkTransferFileAccess();
        TransferFileAccess.Prepared prepared = null;
        try {
            TransferOptions options = options(TransferOptions.ConflictPolicy.OVERWRITE);
            Files.write(root.resolve("file.bin"), OLD_BYTES);
            prepared = prepare(fs, root, options);
            Path part = Paths.get(fs.stagingPath(prepared.token));
            Path meta = metadata(part);
            fill(fs, prepared);
            try (FileChannel held = FileChannel.open(meta, StandardOpenOption.READ, noShareDelete)) {
                fs.commit(prepared.token, true);
                check(Arrays.equals(NEW_BYTES, Files.readAllBytes(root.resolve("file.bin"))),
                        "A real Windows metadata handle cannot falsely fail completed publication");
                check(!Files.exists(part), "No unpublished part remains after successful publication");
                check(fs.takeWarnings().size() == 1, "Windows sharing violation becomes a cleanup warning");
                boolean deniedWhileHeld = false;
                try { prepare(fs, root, options); }
                catch (IOException expected) { deniedWhileHeld = true; }
                check(deniedWhileHeld && !Files.exists(part),
                        "Retry while metadata is still held does not create an unusable zero-byte part");
            }
            prepared = prepare(fs, root, options);
            check("ready".equals(prepared.status) && prepared.offset == 0,
                    "A following transfer recovers safely from post-publication metadata residue");
            check(Arrays.equals(NEW_BYTES, Files.readAllBytes(root.resolve("file.bin"))),
                    "Recovering metadata never removes the published target");
        } finally {
            if (prepared != null) fs.abandon(prepared.token, false);
            cleanup(root);
        }
    }

    private static void publishedHardLinkAndMetadataResidue() throws Exception {
        Path root = Files.createTempDirectory("hfx-linked-publication-");
        JdkTransferFileAccess fs = new JdkTransferFileAccess() {
            @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
                if (source.getName().endsWith(".part")) {
                    // The final name is successfully published, but unlinking
                    // the staging name fails: both names now share one inode.
                    Files.createLink(target.toPath(), source.toPath());
                } else {
                    super.atomicMove(source, target, replace);
                }
            }
        };
        TransferFileAccess.Prepared prepared = null;
        try {
            TransferOptions options = options(TransferOptions.ConflictPolicy.OVERWRITE);
            prepared = prepare(fs, root, options);
            Path part = Paths.get(fs.stagingPath(prepared.token));
            Path meta = metadata(part);
            Path target = root.resolve("file.bin");
            fill(fs, prepared);
            byte[] savedMetadata = Files.readAllBytes(meta);
            boolean windows = File.separatorChar == '\\';
            OpenOption noShareDelete = null;
            if (windows) {
                for (Object constant : Class.forName("com.sun.nio.file.ExtendedOpenOption").getEnumConstants())
                    if ("NOSHARE_DELETE".equals(constant.toString())) noShareDelete = (OpenOption) constant;
                check(noShareDelete != null, "Windows combined-failure test can deny metadata deletion");
            }
            try (FileChannel held = windows
                    ? FileChannel.open(meta, StandardOpenOption.READ, noShareDelete) : null) {
                fs.commit(prepared.token, true);
                check(Arrays.equals(NEW_BYTES, Files.readAllBytes(target)),
                        "Both cleanup failures leave a successfully published complete file");
                check(fs.takeWarnings().size() == (windows ? 2 : 1) && fs.takeWarnings().isEmpty(),
                        "Publication reports each cleanup warning once without failing");
            }
            // POSIX allows unlinking an open file. Restore the valid metadata
            // snapshot to reproduce the same two-residue state on those hosts.
            if (!windows) Files.write(meta, savedMetadata);
            check(Files.isSameFile(part, target), "Test residues actually share the published file's inode");
            check(Files.exists(meta), "Valid checkpoint remains beside the linked part");
            options.resume = false;
            boolean rejected = false;
            try { prepared = prepare(fs, root, options); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "Overwrite without resume rejects a part linked to the published file");
            check(Arrays.equals(NEW_BYTES, Files.readAllBytes(target)),
                    "Rejected retry never truncates the already completed destination");
            check(Arrays.equals(NEW_BYTES, Files.readAllBytes(part)),
                    "Rejected retry preserves the linked staging bytes");
            check(Arrays.equals(savedMetadata, Files.readAllBytes(meta)),
                    "Rejected retry leaves its original checkpoint untouched");
        } finally {
            if (prepared != null) fs.abandon(prepared.token, true);
            cleanup(root);
        }
    }

    private static final class Pair implements AutoCloseable {
        final SocketChannel left, right;
        Pair() throws IOException {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                left = SocketChannel.open(listener.getLocalAddress());
                right = listener.accept();
                left.socket().setTcpNoDelay(true); right.socket().setTcpNoDelay(true);
            }
        }
        @Override public void close() throws IOException { left.close(); right.close(); }
    }

    private static final class Observer implements TransferFileCallback {
        int completed, incomplete;
        final List<String> results = new ArrayList<>();
        @Override public void onFileResult(String path, String status, String message) {
            results.add(status + ":" + message);
        }
        @Override public void onFileUploading(String name, String path, long current, long total) { }
        @Override public void onFileDownloading(String name, String path, long current, long total) { }
        @Override public void onSpeedInfo(List<TrafficInfo> info) { }
        @Override public void onChannelComplete(String name, long bytes, long elapsed) { }
        @Override public void onChannelError(String name, int type, String message) { }
        @Override public void onReadFileError(String message) { }
        @Override public void onWriteFileError(String message) { }
        @Override public void onComplete(boolean upload, long bytes, long elapsed) { completed++; }
        @Override public void onIncomplete() { incomplete++; }
    }

    private static void receiverPolicy(TransferOptions.ConflictPolicy proposed,
                                       TransferOptions.ConflictPolicy configured,
                                       boolean explicit, boolean expectOverwrite) throws Exception {
        transferResult(proposed, configured, explicit, expectOverwrite, new JdkTransferFileAccess(), false);
    }

    private static void transferResult(TransferOptions.ConflictPolicy proposed,
                                       TransferOptions.ConflictPolicy configured,
                                       boolean explicit, boolean expectOverwrite,
                                       TransferFileAccess destinationFiles, boolean expectedFailure) throws Exception {
        Path source = Files.createTempDirectory("hfx-options-source-");
        Path target = Files.createTempDirectory("hfx-options-target-");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ReliableTransfer sender = null, receiver = null;
        try (Pair control = new Pair(); Pair data = new Pair()) {
            Files.write(source.resolve("file.bin"), NEW_BYTES);
            Files.write(target.resolve("file.bin"), OLD_BYTES);
            Observer sent = new Observer(), received = new Observer();
            sender = new ReliableTransfer(new JdkTransferFileAccess(), new DataByteChannel(control.left),
                    Collections.singletonList(new TransferConnection("loopback", new DataByteChannel(data.left))),
                    new LinkedBlockingDeque<>(), sent);
            receiver = new ReliableTransfer(destinationFiles, new DataByteChannel(control.right),
                    Collections.singletonList(new TransferConnection("loopback", new DataByteChannel(data.right))),
                    new LinkedBlockingDeque<>(), received);
            ReliableTransfer sending = sender, receiving = receiver;
            Future<Boolean> receive = executor.submit(() -> receiving.receive(options(configured), explicit));
            Future<Boolean> send = executor.submit(() -> sending.send(
                    Collections.singletonList(new RemoteFile(source.resolve("file.bin").toFile())),
                    new Directory(source.toString(), Directory.getCurrentFileSystem()),
                    new Directory(target.toString(), Directory.getCurrentFileSystem()), options(proposed)));
            check(send.get(10, TimeUnit.SECONDS) && receive.get(10, TimeUnit.SECONDS),
                    "Negotiated option transfer leaves the control session usable");
            check(Arrays.equals(expectOverwrite ? NEW_BYTES : OLD_BYTES,
                            Files.readAllBytes(target.resolve("file.bin"))),
                    "Receiver explicit=" + explicit + " selects the expected conflict policy");
            for (TransferSummary summary : Arrays.asList(sender.getSummary(), receiver.getSummary())) {
                check(summary.failedFiles == (expectedFailure ? 1 : 0)
                                && summary.completedFiles == (expectOverwrite ? 1 : 0)
                                && summary.skippedFiles == (expectedFailure || expectOverwrite ? 0 : 1),
                        "Both sides report the negotiated policy's actual result");
            }
            check(sent.completed == (expectedFailure ? 0 : 1) && received.completed == (expectedFailure ? 0 : 1)
                            && sent.incomplete == (expectedFailure ? 1 : 0) && received.incomplete == (expectedFailure ? 1 : 0),
                    "Completion callbacks distinguish publication failure from post-publication cleanup");
            if (destinationFiles instanceof CleanupFaultFiles) {
                check(receiver.getSummary().warnings.size() == 1
                                && sender.getSummary().warnings.equals(receiver.getSummary().warnings)
                                && receiver.getSummary().isSuccessful() && sender.getSummary().isSuccessful(),
                        "Both summaries preserve identical cleanup warnings without changing successful status");
                check(received.results.stream().filter(message -> message.startsWith("warning:")).count() == 1,
                        "Receiver receives the cleanup warning event exactly once");
                check(sent.results.stream().filter(message -> message.startsWith("warning:")).count() == 1,
                        "Sender receives the structured cleanup warning event exactly once");
                check(destinationFiles.takeWarnings().isEmpty(), "Summary drains filesystem warnings");
            } else {
                check(sender.getSummary().warnings.isEmpty() && receiver.getSummary().warnings.isEmpty(),
                        "A normal result or real publication error does not invent cleanup warnings");
            }
        } finally {
            if (sender != null) sender.cancel();
            if (receiver != null) receiver.cancel();
            executor.shutdownNow();
            check(executor.awaitTermination(5, TimeUnit.SECONDS), "Option test workers terminate");
            cleanup(source); cleanup(target);
        }
    }

    public static void main(String[] args) throws Exception {
        destinationAppearedDuringTransfer();
        receiverPolicy(TransferOptions.ConflictPolicy.OVERWRITE, TransferOptions.ConflictPolicy.SKIP, true, false);
        receiverPolicy(TransferOptions.ConflictPolicy.SKIP, TransferOptions.ConflictPolicy.OVERWRITE, true, true);
        receiverPolicy(TransferOptions.ConflictPolicy.SKIP, TransferOptions.ConflictPolicy.OVERWRITE, false, false);
        transferResult(TransferOptions.ConflictPolicy.OVERWRITE, TransferOptions.ConflictPolicy.OVERWRITE,
                true, false, new JdkTransferFileAccess() {
                    @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
                        if (source.getName().endsWith(".part")) throw new IOException("Synthetic publication failure");
                        super.atomicMove(source, target, replace);
                    }
                }, true);
        postPublicationCleanupFailure();
        windowsMetadataHandleAndNextTransfer();
        publishedHardLinkAndMetadataResidue();
        transferResult(TransferOptions.ConflictPolicy.OVERWRITE, TransferOptions.ConflictPolicy.OVERWRITE,
                true, true, new CleanupFaultFiles(), false);
        System.out.println("TransferProtectionTest: " + checks + " checks passed");
    }
}
