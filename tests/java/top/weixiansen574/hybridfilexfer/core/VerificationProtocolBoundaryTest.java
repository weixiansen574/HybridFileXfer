package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import top.weixiansen574.nio.DataByteChannel;

/** Loopback regressions at the finalization boundary; no filesystem or user data. */
public final class VerificationProtocolBoundaryTest {
    // Protocol 401 commands used by the independent scripted peer.
    private static final int FINALIZE = 46;
    private static final AtomicInteger checks = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        receiverStopDuringFinalizationReachesBothEndpoints();
        finalizationCannotUpgradeFailureToVerified(VerificationCoordinator.FAILED);
        finalizationCannotUpgradeFailureToVerified(VerificationCoordinator.MISMATCH);
        System.out.println("VerificationProtocolBoundaryTest: " + checks.get() + " checks passed");
    }

    private static void receiverStopDuringFinalizationReachesBothEndpoints() throws Exception {
        // Hashing has already concluded successfully. The receiver stops only when
        // its final outcome is about to be written, after the last polling boundary.
        VerificationCoordinator.File sourceFile = file(VerificationCoordinator.VERIFIED);
        VerificationCoordinator.File targetFile = file(VerificationCoordinator.VERIFIED);
        TransferSummary sourceSummary = summary();
        TransferSummary targetSummary = summary();
        Host sourceHost = new Host();
        AtomicBoolean reachedFinalization = new AtomicBoolean();
        Host targetHost = new Host() {
            @Override public List<String> collectWarnings(String path) {
                reachedFinalization.set(true);
                stop();
                return Collections.emptyList();
            }
        };
        try (Pair pair = new Pair()) {
            VerificationCoordinator sender = coordinator(pair.source, sourceFile, sourceSummary, sourceHost);
            VerificationCoordinator receiver = coordinator(pair.target, targetFile, targetSummary, targetHost);
            Future<?> receiving = pair.run(receiver::receive);
            Future<?> sending = pair.run(sender::send);
            await(sending);
            await(receiving);
            check(reachedFinalization.get(), "The stop must occur at finalization");
            check(sourceHost.stopping(), "The sender must adopt the final peer stop flag");
            check(sourceSummary.verificationStopped && targetSummary.verificationStopped,
                    "Both endpoints must report the same stopped outcome");
            check(sourceSummary.verifiedFiles == 1 && targetSummary.verifiedFiles == 1,
                    "A late stop must preserve already verified conclusions");
            check(sourceSummary.failedFiles == 0 && targetSummary.failedFiles == 0,
                    "A late stop must not invent file failures");
            check(!sourceSummary.isSuccessful() && !targetSummary.isSuccessful(),
                    "Neither endpoint may report normal success after the agreed stop");

            // A reusable connection must have consumed the complete final response.
            // Execute the probe in bounded workers so a regression cannot hang CI.
            Future<?> probe = pair.run(() -> {
                pair.target.writeInt(0x5a170123);
                check(pair.source.readInt() == 0x5a170123,
                        "The final stop acknowledgment must leave no unread protocol bytes");
                pair.source.writeInt(0x731a4567);
                check(pair.target.readInt() == 0x731a4567,
                        "The reverse control stream must remain aligned");
            });
            await(probe);
        }
    }

    private static void finalizationCannotUpgradeFailureToVerified(int originalState) throws Exception {
        VerificationCoordinator.File sourceFile = file(originalState);
        TransferSummary sourceSummary = summary();
        try (Pair pair = new Pair()) {
            VerificationCoordinator sender = coordinator(pair.source, sourceFile, sourceSummary, new Host());
            Future<?> peer = pair.run(() -> {
                check(pair.target.readInt() == FINALIZE, "Expected the finalization command");
                check(!pair.target.readBoolean(), "This fixture did not request a soft stop");
                check(pair.target.readInt() == 1 && pair.target.readInt() == sourceFile.id,
                        "Finalization must address the known file");
                pair.target.writeInt(1);
                pair.target.writeInt(sourceFile.id);
                pair.target.writeInt(VerificationCoordinator.VERIFIED);
                pair.target.writeUTF("");
                pair.target.writeUTF("Synthetic invalid final upgrade");
                pair.target.writeInt(0);
            });
            Future<?> sending = pair.run(sender::send);
            IOException rejection = null;
            try {
                await(sending);
            } catch (IOException expected) {
                rejection = expected;
            }
            await(peer);
            check(rejection != null && rejection.getMessage().contains("verification conclusion"),
                    "A final outcome cannot replace a known failure with VERIFIED");
            check(sourceFile.state == originalState, "Rejected finalization must preserve the existing conclusion");
            check(sourceSummary.verifiedFiles == 0 && sourceSummary.verificationFailedFiles == 1,
                    "Rejected finalization must not credit a verified file");
        }
    }

    private static VerificationCoordinator.File file(int state) {
        VerificationCoordinator.File file = new VerificationCoordinator.File(7, "synthetic/file.bin",
                "", "", "", 0, 0);
        file.state = state;
        file.message = "Synthetic existing conclusion";
        return file;
    }

    private static TransferSummary summary() {
        TransferSummary summary = new TransferSummary();
        summary.verificationEnabled = true;
        return summary;
    }

    private static VerificationCoordinator coordinator(DataByteChannel channel,
            VerificationCoordinator.File file, TransferSummary summary, Host host) throws IOException {
        TransferFileAccess unusedFilesystem = (TransferFileAccess) Proxy.newProxyInstance(
                TransferFileAccess.class.getClassLoader(), new Class<?>[] {TransferFileAccess.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected filesystem call: " + method.getName()); });
        return new VerificationCoordinator(unusedFilesystem, channel, Collections.singletonList(file), summary, host);
    }

    private static class Host implements VerificationCoordinator.Host {
        private final AtomicBoolean stopped = new AtomicBoolean();
        @Override public void checkCancelled() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Test interrupted");
        }
        @Override public boolean stopping() { return stopped.get(); }
        @Override public void stop() { stopped.set(true); }
        @Override public void progress(VerificationProgress progress) { }
        @Override public void result(String path, String status, String message) { }
        @Override public List<String> collectWarnings(String path) { return Collections.emptyList(); }
        @Override public void warning(String path, String message) { }
        @Override public void retry(VerificationCoordinator.File file, boolean sending) {
            throw new AssertionError("Finalization must not restart a transfer");
        }
    }

    private interface Work { void run() throws Exception; }

    private static final class Pair implements AutoCloseable {
        final DataByteChannel source, target;
        final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "HFX-verification-boundary-test");
            thread.setDaemon(true);
            return thread;
        });
        Pair() throws IOException {
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                SocketChannel first = SocketChannel.open(listener.getLocalAddress());
                try {
                    source = new DataByteChannel(first);
                    target = new DataByteChannel(listener.accept());
                } catch (IOException | RuntimeException failure) {
                    first.close();
                    throw failure;
                }
            }
        }
        Future<?> run(Work work) { return workers.submit(() -> { work.run(); return null; }); }
        @Override public void close() throws Exception {
            try { source.close(); }
            finally {
                try { target.close(); }
                finally {
                    workers.shutdownNow();
                    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Protocol test workers did not exit");
                    }
                }
            }
        }
    }

    private static void await(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Verification protocol boundary timed out", timeout);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.incrementAndGet();
    }
}
