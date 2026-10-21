import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import top.weixiansen574.hybridfilexfer.core.*;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkTransferFileAccess;
import top.weixiansen574.hybridfilexfer.core.bean.*;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

/** Loopback protocol tests use generated local bytes only. No devices or user data. */
public final class TransferReliabilityTest {
    private static int passed;
    private static boolean encrypt;
    private static final int MB=1024*1024;
    private static final class TaskReport {
        final String channel,path;
        final boolean upload;
        final long offset,length,size;
        TaskReport(String channel,String path,boolean upload,long offset,long length,long size) {
            this.channel=channel; this.path=path; this.upload=upload;
            this.offset=offset; this.length=length; this.size=size;
        }
    }
    private static final class Observer implements TransferFileCallback {
        final List<String> states=new CopyOnWriteArrayList<>();
        volatile TransferSummary summary;
        volatile TransferProgress progress;
        volatile boolean throwCallbacks;
        final Map<String,Long> channelBytes=new ConcurrentHashMap<>();
        volatile int completedChannels;
        volatile boolean validChannelCallbacks=true;
        final List<TaskReport> taskReports=new CopyOnWriteArrayList<>();
        final Set<String> activeTasks=ConcurrentHashMap.newKeySet();
        volatile boolean validTaskLifecycle=true;
        Runnable progressHook;
        @Override public void onProgress(TransferProgress p) {
            progress=p;
            if(progressHook!=null) progressHook.run();
            if(throwCallbacks) throw new IllegalStateException("Synthetic UI failure");
        }
        @Override public void onSummary(TransferSummary s) {
            if(!activeTasks.isEmpty()) validTaskLifecycle=false;
            summary=s;
        }
        @Override public void onFileResult(String path,String status,String message) { states.add(status+":"+path); }
        @Override public void onFileUploading(String i,String p,long n,long t) {}
        @Override public void onFileDownloading(String i,String p,long n,long t) {}
        @Override public void onChannelTask(String name,String path,boolean upload,long offset,long length,long size) {
            if(summary!=null || path==null || path.isEmpty() || offset<0 || length<=0 || length>MB
                    || offset>size-length) validTaskLifecycle=false;
            taskReports.add(new TaskReport(name,path,upload,offset,length,size));
            activeTasks.add(name);
            if(throwCallbacks) throw new IllegalStateException("Synthetic task UI failure");
        }
        @Override public void onChannelIdle(String name) {
            activeTasks.remove(name);
            if(throwCallbacks) throw new IllegalStateException("Synthetic idle UI failure");
        }
        @Override public void onSpeedInfo(List<TrafficInfo> t) {}
        @Override public void onChannelComplete(String i,long n,long t) {
            if (summary!=null || n<0 || t<0 || channelBytes.put(i,n)!=null) validChannelCallbacks=false;
            if(activeTasks.contains(i)) validTaskLifecycle=false;
            completedChannels++;
            if(throwCallbacks) throw new IllegalStateException("Synthetic channel UI failure");
        }
        @Override public void onChannelError(String i,int t,String m) {
            if(activeTasks.contains(i)) validTaskLifecycle=false;
        }
        @Override public void onReadFileError(String m) {}
        @Override public void onWriteFileError(String m) {}
        @Override public void onComplete(boolean u,long n,long t) {}
        @Override public void onIncomplete() {}
    }
    private static final class Result {
        TransferSummary sent,received;
        Observer sender,receiver;
    }
    private static final class Pair implements AutoCloseable {
        final ByteChannel left,right;
        Pair() throws IOException {
            try(ServerSocketChannel server=ServerSocketChannel.open()) {
                server.bind(new InetSocketAddress("127.0.0.1",0));
                SocketChannel a=SocketChannel.open(server.getLocalAddress()),b=server.accept();
                a.socket().setTcpNoDelay(true); b.socket().setTcpNoDelay(true);
                if (encrypt) {
                    byte[] aKey=new byte[32],bKey=new byte[32],aSalt={1,2,3,4},bSalt={5,6,7,8};
                    Arrays.fill(aKey,(byte)11); Arrays.fill(bKey,(byte)22);
                    left=new top.weixiansen574.hybridfilexfer.security.SecureRecordChannel(a,aKey,bKey,aSalt,bSalt);
                    right=new top.weixiansen574.hybridfilexfer.security.SecureRecordChannel(b,bKey,aKey,bSalt,aSalt);
                } else { left=a; right=b; }
            }
        }
        public void close() throws IOException { left.close(); right.close(); }
    }
    private static class Limited implements ByteChannel {
        final ByteChannel delegate;
        final int max;
        long remaining;
        Limited(ByteChannel delegate,int max,long remaining) { this.delegate=delegate; this.max=max; this.remaining=remaining; }
        public int read(ByteBuffer b) throws IOException { return delegate.read(b); }
        public int write(ByteBuffer b) throws IOException {
            if(remaining<=0) { close(); throw new EOFException("Synthetic lost channel"); }
            int limit=b.limit();
            b.limit(b.position()+(int)Math.min(Math.min(b.remaining(),max),remaining));
            try { int n=delegate.write(b); remaining-=n; return n; }
            finally { b.limit(limit); }
        }
        public boolean isOpen() { return delegate.isOpen(); }
        public void close() throws IOException { delegate.close(); }
    }
    private static Result run(Path source,Path destination,List<String> names,TransferOptions options,int channels,
                              Function<ByteChannel,ByteChannel> firstSender,TransferFileAccess destinationFs,
                              Observer sendObserver,Observer receiveObserver) throws Exception {
        List<Pair> pairs=new ArrayList<>();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        ReliableTransfer sender=null,receiver=null;
        try {
            Pair control=new Pair(); pairs.add(control);
            List<TransferConnection> sends=new ArrayList<>(),receives=new ArrayList<>();
            for(int i=0;i<channels;i++) {
                Pair pair=new Pair(); pairs.add(pair);
                sends.add(new TransferConnection("test-"+i,new DataByteChannel(i==0?firstSender.apply(pair.left):pair.left)));
                receives.add(new TransferConnection("test-"+i,new DataByteChannel(pair.right)));
            }
            sender=new ReliableTransfer(new JdkTransferFileAccess(),new DataByteChannel(control.left),sends,
                    new LinkedBlockingDeque<>(),sendObserver);
            receiver=new ReliableTransfer(destinationFs,new DataByteChannel(control.right),receives,
                    new LinkedBlockingDeque<>(),receiveObserver);
            List<RemoteFile> files=new ArrayList<>();
            for(String name:names) files.add(new RemoteFile(source.resolve(name).toFile()));
            ReliableTransfer a=sender,b=receiver;
            Future<Boolean> r=executor.submit(()->b.receive(options,true));
            Future<Boolean> s=executor.submit(()->a.send(files,new Directory(source.toString(),Directory.getCurrentFileSystem()),
                    new Directory(destination.toString(),Directory.getCurrentFileSystem()),options));
            s.get(35,TimeUnit.SECONDS); r.get(35,TimeUnit.SECONDS);
            Result result=new Result(); result.sent=sender.getSummary(); result.received=receiver.getSummary();
            result.sender=sendObserver; result.receiver=receiveObserver;
            return result;
        } finally {
            if(sender!=null) sender.cancel();
            if(receiver!=null) receiver.cancel();
            for(Pair pair:pairs) pair.close();
            executor.shutdownNow(); executor.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
    private static Result run(Path source,Path destination,List<String> names,TransferOptions options,int channels,
                              Function<ByteChannel,ByteChannel> fault,TransferFileAccess fs) throws Exception {
        return run(source,destination,names,options,channels,fault,fs,new Observer(),new Observer());
    }
    private static TransferOptions options() {
        TransferOptions o=new TransferOptions(); o.conflictPolicy=TransferOptions.ConflictPolicy.OVERWRITE;
        o.verifySha256=true;
        o.timeoutSeconds=5; return o;
    }
    private static Path temp(String name) throws IOException { return Files.createTempDirectory("hfx-"+name+"-"); }
    private static void bytes(Path path,int length,int seed) throws IOException {
        byte[] bytes=new byte[length]; new Random(seed).nextBytes(bytes); Files.write(path,bytes);
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); passed++; }
    private static boolean identical(Path a,Path b) throws IOException {
        return Files.size(a)==Files.size(b) && Arrays.equals(Files.readAllBytes(a),Files.readAllBytes(b));
    }
    private static void cleanup(Path root) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
    private static Path part(Path root) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.list(root)) {
            return paths.filter(p->p.getFileName().toString().endsWith(".part")).findFirst()
                    .orElseThrow(()->new IOException("Expected partial file"));
        }
    }
    private static void normalAndShortWrites() throws Exception {
        Path source=temp("source"),target=temp("target");
        try {
            bytes(source.resolve("large.bin"),5*MB+137,1);
            Files.write(source.resolve("empty.bin"),new byte[0]);
            Files.createDirectory(source.resolve("empty-dir"));
            TransferOptions o=options(); o.prescan=true;
            Observer observer=new Observer(); observer.throwCallbacks=true;
            Result r=run(source,target,Arrays.asList("large.bin","empty.bin","empty-dir"),o,3,
                    c->new Limited(c,997,Long.MAX_VALUE),new JdkTransferFileAccess(),observer,new Observer());
            check(r.sent.failedFiles==0 && r.received.failedFiles==0,"Normal multichannel transfer failed");
            check(identical(source.resolve("large.bin"),target.resolve("large.bin")),"Short writes lost bytes");
            check(Files.size(target.resolve("empty.bin"))==0 && Files.isDirectory(target.resolve("empty-dir")),"Empty entries missing");
            check(r.sent.completedFiles==2 && r.sent.transferredBytes==5L*MB+137,"Wrong confirmed counters");
            check(r.sender.progress.totalBytes==5L*MB+137 && r.sender.progress.totalFiles==2,"Prescan totals wrong");
            check(r.receiver.states.contains("verified:large.bin"),"Verification result missing");
            for (Observer endpoint:Arrays.asList(r.sender,r.receiver)) {
                check(endpoint.completedChannels==3 && endpoint.validChannelCallbacks,
                        "Each channel must complete once before the transfer summary");
                check(endpoint.channelBytes.values().stream().mapToLong(Long::longValue).sum()==5L*MB+137,
                        "Final per-channel byte totals use the wrong transfer direction");
                check(endpoint.taskReports.stream().map(task->task.channel).distinct().count()==3,
                        "Active transfer channels never reported their actual file task");
                check(endpoint.taskReports.stream().allMatch(task->task.path.equals("large.bin")
                                && task.size==5L*MB+137 && task.upload==(endpoint==r.sender)),
                        "Channel task described the wrong file, direction, or size");
                check(endpoint.activeTasks.isEmpty() && endpoint.validTaskLifecycle,
                        "Channel tasks were not cleared before completion");
            }
        } finally { cleanup(source); cleanup(target); }
    }
    private static void lostChannelFallback() throws Exception {
        Path source=temp("fallback-source"),target=temp("fallback-target");
        try {
            bytes(source.resolve("file.bin"),8*MB+71,2);
            Result r=run(source,target,Collections.singletonList("file.bin"),options(),3,
                    c->new Limited(c,16384,MB/3),new JdkTransferFileAccess());
            check(r.sent.failedFiles==0 && r.received.failedFiles==0,"One failed channel aborted whole transfer");
            check(identical(source.resolve("file.bin"),target.resolve("file.bin")),"Fallback result differs");
            check(r.sender.validTaskLifecycle && r.receiver.validTaskLifecycle
                            && r.sender.activeTasks.isEmpty() && r.receiver.activeTasks.isEmpty(),
                    "A retired fallback channel retained a stale task");
        } finally { cleanup(source); cleanup(target); }
    }
    private static void stalledChannelTimeout() throws Exception {
        Path source=temp("timeout-source"),target=temp("timeout-target");
        try {
            bytes(source.resolve("file.bin"),3*MB+71,22);
            long started=System.nanoTime();
            Result r=run(source,target,Collections.singletonList("file.bin"),options(),2,
                    c->new Limited(c,16384,MB/4) {
                        @Override public int write(ByteBuffer b) throws IOException {
                            if(remaining>0) return super.write(b);
                            while(delegate.isOpen()) {
                                try { Thread.sleep(5); }
                                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                            }
                            throw new EOFException("Timeout closed stalled channel");
                        }
                    },new JdkTransferFileAccess());
            check(r.sent.failedFiles==0 && identical(source.resolve("file.bin"),target.resolve("file.bin")),
                    "Silent channel timeout did not recover over healthy channel");
            check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)<15,"Stalled transfer exceeded timeout bound");
        } finally { cleanup(source); cleanup(target); }
    }
    private static void durableResume(boolean corruptPrefix,boolean changeSource) throws Exception {
        Path source=temp("resume-source"),target=temp("resume-target");
        try {
            Path file=source.resolve("file.bin");
            bytes(file,5*MB+43,3);
            Files.write(target.resolve("file.bin"),"original destination".getBytes("UTF-8"));
            Result interrupted=run(source,target,Collections.singletonList("file.bin"),options(),1,
                    c->new Limited(c,65536,2L*MB+1000),new JdkTransferFileAccess());
            check(interrupted.received.failedFiles>0,"Interrupted transfer was marked complete");
            check(new String(Files.readAllBytes(target.resolve("file.bin")),"UTF-8").equals("original destination"),
                    "Failure damaged existing destination");
            Path partial=part(target);
            check(Files.size(partial)>=MB && Files.size(partial)<Files.size(file),"No actual durable partial prefix");
            if(corruptPrefix) try(RandomAccessFile f=new RandomAccessFile(partial.toFile(),"rw")) { f.seek(0); f.write(42); }
            if(changeSource) {
                long time=file.toFile().lastModified();
                bytes(file,5*MB+43,31);
                file.toFile().setLastModified(time);
            }
            Result completed=run(source,target,Collections.singletonList("file.bin"),options(),2,c->c,new JdkTransferFileAccess());
            check(completed.received.failedFiles==0 && identical(file,target.resolve("file.bin")),"Resumed result differs");
            if(corruptPrefix || changeSource) check(completed.sent.resumedBytes==0,"Changed/corrupt prefix was incorrectly trusted");
            else {
                check(completed.sent.resumedBytes>=MB,"Persistent restart did not reuse verified bytes");
                check(completed.sent.transferredBytes<Files.size(file),"Resume retransmitted the whole file");
                check(completed.sender.taskReports.stream().allMatch(task->task.offset>=completed.sent.resumedBytes),
                        "Resumed channel task reported a block from the already verified prefix");
            }
        } finally { cleanup(source); cleanup(target); }
    }
    private static void conflictsAndIncremental() throws Exception {
        Path source=temp("conflict-source"),target=temp("conflict-target");
        try {
            bytes(source.resolve("file.bin"),2*MB,4);
            bytes(target.resolve("file.bin"),2*MB,40);
            TransferOptions o=options(); o.incremental=true;
            Result r=run(source,target,Collections.singletonList("file.bin"),o,2,c->c,new JdkTransferFileAccess());
            check(r.sent.skippedFiles==0 && identical(source.resolve("file.bin"),target.resolve("file.bin")),"Same size was mistaken for identical");
            r=run(source,target,Collections.singletonList("file.bin"),o,2,c->c,new JdkTransferFileAccess());
            check(r.sent.skippedFiles==1 && r.sent.transferredBytes==0,"Strict incremental did not skip identical content");
            bytes(source.resolve("file.bin"),2*MB,41);
            o.incremental=false; o.conflictPolicy=TransferOptions.ConflictPolicy.SKIP;
            r=run(source,target,Collections.singletonList("file.bin"),o,1,c->c,new JdkTransferFileAccess());
            check(r.sent.skippedFiles==1 && !identical(source.resolve("file.bin"),target.resolve("file.bin")),"Skip policy overwrote target");
            o.conflictPolicy=TransferOptions.ConflictPolicy.KEEP_BOTH;
            r=run(source,target,Collections.singletonList("file.bin"),o,2,c->c,new JdkTransferFileAccess());
            check(r.sent.failedFiles==0 && identical(source.resolve("file.bin"),target.resolve("file (1).bin")),"Keep-both failed");
        } finally { cleanup(source); cleanup(target); }
    }
    private static void failureIsolation() throws Exception {
        Path source=temp("failure-source"),target=temp("failure-target");
        try {
            bytes(source.resolve("bad.bin"),2*MB,5); bytes(source.resolve("good.bin"),MB+7,6);
            Files.write(target.resolve("bad.bin"),new byte[]{9,8,7});
            LocalTransferFileAccess faulty=new JdkTransferFileAccess() {
                String brokenToken;
                @Override public synchronized Prepared prepare(String root,String relative,String identity,long size,long modified,
                                                               String hash,TransferOptions o) throws IOException {
                    Prepared p=super.prepare(root,relative,identity,size,modified,hash,o);
                    if(relative.equals("bad.bin")) brokenToken=p.token;
                    return p;
                }
                @Override public synchronized void checkpoint(String token,long offset) throws IOException {
                    if(token.equals(brokenToken)) throw new IOException("Synthetic disk full");
                    super.checkpoint(token,offset);
                }
            };
            Result r=run(source,target,Arrays.asList("bad.bin","good.bin"),options(),2,c->c,faulty);
            check(r.received.failedFiles==1 && r.received.completedFiles==1,"Per-file storage failure was not isolated");
            check(Arrays.equals(Files.readAllBytes(target.resolve("bad.bin")),new byte[]{9,8,7}),"Disk error damaged old destination");
            check(identical(source.resolve("good.bin"),target.resolve("good.bin")),"Following file did not transfer");
            check(r.receiver.taskReports.stream().anyMatch(task->task.path.equals("bad.bin"))
                            && r.receiver.taskReports.stream().anyMatch(task->task.path.equals("good.bin"))
                            && r.receiver.validTaskLifecycle && r.receiver.activeTasks.isEmpty(),
                    "Next file did not replace the failed file's channel task");
        } finally { cleanup(source); cleanup(target); }
    }
    private static void sourceMutation() throws Exception {
        Path source=temp("mutation-source"),target=temp("mutation-target");
        try {
            Path file=source.resolve("file.bin"); bytes(file,4*MB,7);
            Files.write(target.resolve("file.bin"),new byte[]{1,2});
            Observer receive=new Observer();
            java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
            receive.progressHook=()->{
                if(changed.compareAndSet(false,true)) {
                    try(RandomAccessFile f=new RandomAccessFile(file.toFile(),"rw")) { f.seek(0); f.write(123); }
                    catch(IOException e) { throw new RuntimeException(e); }
                }
            };
            Result r=run(source,target,Collections.singletonList("file.bin"),options(),2,c->c,new JdkTransferFileAccess(),new Observer(),receive);
            check(r.received.failedFiles>0,"Source mutation was accepted");
            check(Arrays.equals(Files.readAllBytes(target.resolve("file.bin")),new byte[]{1,2}),"Source mutation replaced old destination");
        } finally { cleanup(source); cleanup(target); }
    }
    private static void safeNamesAndBoundaries() throws Exception {
        TransferPaths mapper=new TransferPaths();
        Directory root=new Directory("/source",Directory.FILE_SYSTEM_UNIX);
        String a=mapper.map("/source/a:b",root),b=mapper.map("/source/a?b",root);
        check(!a.equals(b) && a.equals("a%3Ab"),"Illegal characters collide");
        String upper=mapper.map("/source/A.txt",root),lower=mapper.map("/source/a.txt",root);
        check(!upper.equalsIgnoreCase(lower),"Case-insensitive targets collide");
        check(TransferPaths.encode("CON").startsWith("%5F") && TransferPaths.encode("tail.").endsWith("%2E"),"Reserved names were not escaped");
        for(String name:Arrays.asList("../escape","/absolute","a/../escape","a\\b","CON","a\u0000b","a:stream")) {
            boolean rejected=false; try { TransferPaths.validateRelative(name); } catch(IOException e) { rejected=true; }
            check(rejected,"Unsafe path accepted: "+name);
        }
        Path target=temp("boundary"),outside=temp("outside");
        try {
            LocalTransferFileAccess fs=new JdkTransferFileAccess();
            boolean rejected=false;
            try { fs.prepare(target.toString(),"../escape",TransferHashes.text("x"),1,1,TransferHashes.text("x"),options()); }
            catch(IOException e) { rejected=true; }
            check(rejected && !Files.exists(target.getParent().resolve("escape")),"Destination traversal was not rejected");
            try {
                Files.createSymbolicLink(target.resolve("link"),outside);
                rejected=false;
                try { fs.createDirectory(target.toString(),"link/escape",0); } catch(IOException e) { rejected=true; }
                check(rejected && !Files.exists(outside.resolve("escape")),"Destination symlink escaped root");
            } catch(FileSystemException | UnsupportedOperationException e) {
                System.out.println("SKIP symlink privilege unavailable: "+e.getClass().getSimpleName());
            }
        } finally { cleanup(target); cleanup(outside); }
    }
    private static void badFinalHashAndLargeMetadata() throws Exception {
        Path target=temp("hash-target");
        LocalTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=fs.prepare(target.toString(),"file.bin",TransferHashes.text("identity"),3,1,
                    TransferHashes.text("wrong bytes"),options());
            try(TransferFileAccess.Handle file=fs.openStage(p.token)) {
                file.channel.write(ByteBuffer.wrap(new byte[]{1,2,3})); file.channel.force(true);
            }
            fs.checkpoint(p.token,3);
            boolean rejected=false; try { fs.commit(p.token,true); } catch(IOException e) { rejected=true; }
            check(rejected && !Files.exists(target.resolve("file.bin")),"Bad final hash was published");
            fs.abandon(p.token,false);
            long huge=5L*1024*1024*1024*1024;
            p=fs.prepare(target.toString(),"huge.bin",TransferHashes.text("huge"),huge,1,"",options());
            check(p.offset==0 && Files.size(Paths.get(fs.stagingPath(p.token)))==0,"Large file was preallocated or narrowed to int");
            fs.abandon(p.token,false);
        } finally { cleanup(target); }
    }
    private static void filesystemCancellation() throws Exception {
        Path target=temp("cancel-filesystem");
        LocalTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            fs.beginSession();
            TransferFileAccess.Prepared p=fs.prepare(target.toString(),"empty.bin",TransferHashes.text("empty"),0,1,
                    TransferHashes.text(""),options());
            fs.cancelPendingOperations();
            boolean cancelled=false;
            try { fs.commit(p.token,false); } catch(IOException e) { cancelled=true; }
            check(cancelled && !Files.exists(target.resolve("empty.bin")),"Filesystem cancellation allowed final commit");
            fs.abandon(p.token,true);
            fs.beginSession();
            p=fs.prepare(target.toString(),"empty.bin",TransferHashes.text("empty"),0,1,TransferHashes.text(""),options());
            fs.commit(p.token,true);
            check(Files.exists(target.resolve("empty.bin")),"New session inherited old cancellation");
            Path hashSource=target.resolve("hash.bin");
            Files.write(hashSource,new byte[MB]);
            try (FileChannel input=FileChannel.open(hashSource,StandardOpenOption.READ)) {
                int[] polls={0};
                boolean stopped=false;
                try { TransferHashes.hash(input,MB,() -> ++polls[0]>=3); }
                catch(IOException e) { stopped=true; }
                check(stopped && polls[0]==3 && !Thread.currentThread().isInterrupted(),
                        "Filesystem hash ignored cancellation from a different execution context");
            }
        } finally { cleanup(target); }
    }
    private static void cancellation() throws Exception {
        Path source=temp("cancel-source"),target=temp("cancel-target");
        Pair control=new Pair(),data=new Pair();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        ReliableTransfer sender=null,receiver=null;
        try {
            bytes(source.resolve("file.bin"),8*MB,8);
            Files.write(target.resolve("file.bin"),new byte[]{7});
            Observer observer=new Observer();
            sender=new ReliableTransfer(new JdkTransferFileAccess(),new DataByteChannel(control.left),
                    Collections.singletonList(new TransferConnection("one",new DataByteChannel(data.left))),new LinkedBlockingDeque<>(),new Observer());
            receiver=new ReliableTransfer(new JdkTransferFileAccess(),new DataByteChannel(control.right),
                    Collections.singletonList(new TransferConnection("one",new DataByteChannel(data.right))),new LinkedBlockingDeque<>(),observer);
            ReliableTransfer a=sender,b=receiver;
            observer.progressHook=()->{ if(observer.progress.currentFileBytes>=MB) b.cancel(); };
            Future<?> r=pool.submit(()->{ try { b.receive(options(),true); } catch(IOException e) { throw new RuntimeException(e); } });
            Future<?> s=pool.submit(()->{ try {
                a.send(Collections.singletonList(new RemoteFile(source.resolve("file.bin").toFile())),
                        new Directory(source.toString(),Directory.getCurrentFileSystem()),
                        new Directory(target.toString(),Directory.getCurrentFileSystem()),options());
            } catch(IOException e) { throw new RuntimeException(e); } });
            r.get(15,TimeUnit.SECONDS); s.get(15,TimeUnit.SECONDS);
            check(receiver.getSummary().cancelled,"Cancel was not reflected in summary");
            check(Arrays.equals(Files.readAllBytes(target.resolve("file.bin")),new byte[]{7}),"Cancel replaced original");
            Thread.sleep(50);
            boolean workers=Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()
                    && (t.getName().startsWith("HFX-send-") || t.getName().startsWith("HFX-receive-")));
            check(!workers,"Transfer workers survived cancellation");
            check(!observer.taskReports.isEmpty() && observer.activeTasks.isEmpty() && observer.validTaskLifecycle,
                    "Cancellation left an active channel task or emitted a late task");
        } finally {
            if(sender!=null) sender.cancel(); if(receiver!=null) receiver.cancel();
            control.close(); data.close(); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS);
            cleanup(source); cleanup(target);
        }
    }
    public static void main(String[] args) throws Exception {
        normalAndShortWrites();
        encrypt=true;
        try { normalAndShortWrites(); }
        finally { encrypt=false; }
        lostChannelFallback();
        stalledChannelTimeout();
        durableResume(false,false);
        durableResume(true,false);
        durableResume(false,true);
        conflictsAndIncremental();
        failureIsolation();
        sourceMutation();
        safeNamesAndBoundaries();
        badFinalHashAndLargeMetadata();
        filesystemCancellation();
        cancellation();
        System.out.println("TransferReliabilityTest: "+passed+" assertions passed");
    }
}
