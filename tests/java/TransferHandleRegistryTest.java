import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import top.weixiansen574.hybridfilexfer.core.TransferHandleRegistry;

/** Deterministic client descriptor races without Android or real Binder dependencies. */
public final class TransferHandleRegistryTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void lateOpenCannotJoinAnotherSession() throws Exception {
        TransferHandleRegistry registry=new TransferHandleRegistry();
        long oldOpen=registry.beginOpen();
        registry.invalidateAndClose(); registry.beginSession();
        AtomicInteger closed=new AtomicInteger();
        Closeable late=closed::incrementAndGet;
        check(!registry.register(oldOpen,"old-token",late),"A delayed old RPC cannot register in a new session");
        late.close();
        check(closed.get()==1,"The caller can immediately close the rejected descriptor");
        long current=registry.beginOpen();
        Closeable live=closed::incrementAndGet;
        check(registry.register(current,"new-token",live),"A new session can register its own descriptor");
        boolean rejected=false;
        try { registry.requireClosed("new-token"); } catch (IOException expected) { rejected=true; }
        check(rejected,"State changes reject an active descriptor for the same token");
        registry.requireClosed("other-token");
        registry.invalidateAndClose();
        check(closed.get()==2,"Ending the new session closes its descriptor");
        long starting=registry.invalidate();
        registry.invalidate();
        rejected=false;
        try { registry.beginSession(starting); } catch (IOException expected) { rejected=true; }
        check(rejected,"Cancellation during begin RPC prevents the late session from reopening");
    }
    private static void closeRunsOutsideTheMonitor() throws Exception {
        TransferHandleRegistry registry=new TransferHandleRegistry();
        CountDownLatch closing=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        AtomicBoolean signalled=new AtomicBoolean();
        Closeable owner=()->{
            if (!signalled.get()) throw new AssertionError("Remote cancellation must precede descriptor close");
            closing.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IOException("Close timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        };
        registry.register(registry.beginOpen(),"token",owner);
        try {
            Future<?> ending=executor.submit(()->{ registry.invalidateAndClose(()->signalled.set(true)); return null; });
            check(closing.await(2,TimeUnit.SECONDS),"Descriptor close starts after the cancellation signal");
            Future<Boolean> blocked=executor.submit(()->{
                try { registry.beginOpen(); return false; } catch (IOException expected) { return true; }
            });
            check(blocked.get(2,TimeUnit.SECONDS),"A blocked close does not hold the registry monitor");
            boolean rejected=false;
            try { registry.beginSession(); } catch (IOException expected) { rejected=true; }
            check(rejected,"A new session cannot overtake a descriptor still closing");
            release.countDown(); ending.get(2,TimeUnit.SECONDS);
            registry.beginSession(); registry.beginOpen();
            check(true,"A new session can start after closure completes");
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5,TimeUnit.SECONDS); }
    }
    private static void failedCloseDoesNotSkipOthers() throws Exception {
        TransferHandleRegistry registry=new TransferHandleRegistry();
        AtomicInteger attempts=new AtomicInteger();
        registry.register(registry.beginOpen(),"first",()->{ attempts.incrementAndGet(); throw new IOException("Synthetic close failure"); });
        registry.register(registry.beginOpen(),"second",attempts::incrementAndGet);
        boolean failed=false;
        try { registry.invalidateAndClose(); } catch (IOException expected) { failed=true; }
        check(failed && attempts.get()==2,"A failing descriptor still permits closure of the remaining descriptors");
        registry.beginSession();
        registry.register(registry.beginOpen(),"third",attempts::incrementAndGet);
        failed=false;
        try { registry.invalidateAndClose(()->{ throw new IllegalStateException("Synthetic signal failure"); }); }
        catch (IOException expected) { failed=true; }
        check(failed && attempts.get()==3,"A failed cancellation signal cannot skip descriptor cleanup");
    }
    public static void main(String[] args) throws Exception {
        lateOpenCannotJoinAnotherSession(); closeRunsOutsideTheMonitor(); failedCloseDoesNotSkipOthers();
        System.out.println("TransferHandleRegistryTest: "+checks+" checks passed");
    }
}
