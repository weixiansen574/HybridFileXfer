package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.nio.DataByteChannel;

/**
 * Post-transfer SHA-256, with one control-channel owner and a bounded two-sided window.
 * Polls carry results and soft-stop requests; hashing never blocks the control owner.
 */
final class VerificationCoordinator {
    static final int PENDING=0, VERIFIED=1, MISMATCH=2, FAILED=3, UNVERIFIED=4;
    private static final int START=40, WINDOW=41, POLL=42, WINDOW_END=43,
            ROUND_END=44, RETRY=45, FINALIZE=46, FINISH=47;
    private static final int HASH_OK=1, HASH_IO=2, SOURCE_CHANGED=3, HASH_CANCELLED=4;
    private static final int WINDOW_SIZE=8, WORKERS=2;

    static final class File {
        final int id;
        final String path, source, token;
        final long size, modified;
        String target, message="";
        int state=PENDING;
        boolean retried, summarized, retained;
        File(int id,String path,String source,String token,String target,long size,long modified) {
            this.id=id; this.path=path; this.source=source; this.token=token;
            this.target=target; this.size=size; this.modified=modified;
        }
    }
    interface Host {
        void checkCancelled() throws IOException;
        boolean stopping();
        void stop();
        void progress(VerificationProgress progress);
        void result(String path,String status,String message);
        List<String> collectWarnings(String path);
        void warning(String path,String message);
        void retry(File file,boolean sending) throws IOException;
    }
    private static final class Hash {
        final int id,status;
        final String digest,message;
        Hash(int id,int status,String digest,String message) {
            this.id=id; this.status=status; this.digest=digest; this.message=shortText(message);
        }
    }

    private final TransferFileAccess fs;
    private final DataByteChannel control;
    private final List<File> files;
    private final Map<Integer,File> byId=new HashMap<>();
    private final TransferSummary summary;
    private final Host host;
    private int round;
    private long roundTotal,checked,passed,failed;

    VerificationCoordinator(TransferFileAccess fs,DataByteChannel control,List<File> files,
                            TransferSummary summary,Host host) throws IOException {
        this.fs=fs; this.control=control; this.files=files; this.summary=summary; this.host=host;
        for (File file:files) if (byId.put(file.id,file)!=null) throw new IOException("Duplicate verification file ID");
    }
    private void progress(String phase,String path) {
        host.progress(new VerificationProgress(round,roundTotal,checked,passed,failed,phase,path));
    }
    private void round(int number,long count) {
        round=number; roundTotal=count; checked=passed=failed=0;
        summary.verificationRounds=Math.max(summary.verificationRounds,round);
        progress("verifying","");
    }
    private void concluded(File file) {
        checked++;
        if (file.state==VERIFIED) passed++; else failed++;
        host.result(file.path,file.state==VERIFIED?"verified":file.state==MISMATCH?"checksum-mismatch":"failed",file.message);
        progress("verifying",file.path);
    }
    private File file(int id) throws IOException {
        File file=byId.get(id);
        if (file==null) throw new IOException("Unknown verification file ID");
        return file;
    }
    private int count(int maximum) throws IOException {
        int count=control.readInt();
        if (count<0 || count>maximum) throw new IOException("Invalid verification item count");
        return count;
    }
    private List<File> readFiles(int maximum) throws IOException {
        int count=count(maximum);
        List<File> result=new ArrayList<>();
        Set<Integer> seen=new HashSet<>();
        for (int i=0;i<count;i++) {
            File file=file(control.readInt());
            if (!seen.add(file.id)) throw new IOException("Repeated verification file ID");
            result.add(file);
        }
        return result;
    }
    private void writeFiles(List<File> list) throws IOException {
        control.writeInt(list.size());
        for (File file:list) control.writeInt(file.id);
    }
    private static String shortText(String message) {
        if (message==null) return "";
        return message.length()>1024?message.substring(0,1024):message;
    }
    private String readText() throws IOException {
        String text=control.readUTF();
        if (text.length()>1024) throw new IOException("Oversized verification message");
        return text;
    }
    private void writeHash(Hash hash) throws IOException {
        control.writeInt(hash.id); control.writeInt(hash.status);
        control.writeUTF(hash.digest); control.writeUTF(hash.message);
    }
    private Hash readHash() throws IOException {
        int id=control.readInt(),status=control.readInt();
        String digest=readText(),message=readText();
        if (status<HASH_OK || status>HASH_CANCELLED
                || (status==HASH_OK?!digest.matches("[0-9a-f]{64}"):!digest.isEmpty()))
            throw new IOException("Invalid verification hash result");
        return new Hash(id,status,digest,message);
    }
    private void writeOutcome(File file,String digest) throws IOException {
        control.writeInt(file.id); control.writeInt(file.state);
        control.writeUTF(digest); control.writeUTF(shortText(file.message));
        List<String> warnings=host.collectWarnings(file.path);
        control.writeInt(warnings.size());
        for (String warning:warnings) control.writeUTF(shortText(warning));
    }
    private File readOutcome(Map<Integer,Hash> local,boolean verify) throws IOException {
        File file=file(control.readInt());
        int state=control.readInt();
        String digest=readText(),message=readText();
        if (state<VERIFIED || state>UNVERIFIED) throw new IOException("Invalid verification outcome");
        if (!verify && (file.state==PENDING?state!=UNVERIFIED && state!=FAILED
                :state!=file.state && state!=FAILED))
            throw new IOException("Finalization changed a file's verification conclusion");
        if (verify && (state==VERIFIED || state==MISMATCH)) {
            Hash own=local.get(file.id);
            if (own==null || own.status!=HASH_OK || !digest.matches("[0-9a-f]{64}")
                    || (state==VERIFIED)!=own.digest.equals(digest))
                throw new IOException("Peer verification result disagrees with the source digest");
        }
        int warnings=count(100);
        for (int i=0;i<warnings;i++) host.warning(file.path,readText());
        file.state=state; file.message=message;
        return file;
    }
    private List<File> matching(int state) {
        List<File> matches=new ArrayList<>();
        for (File file:files) if (file.state==state) matches.add(file);
        return matches;
    }

    void send() throws IOException {
        List<File> candidates=matching(PENDING);
        try {
            while (!candidates.isEmpty() && !host.stopping()) {
                round(round+1,candidates.size());
                control.writeInt(START); control.writeInt(round); control.writeLong(roundTotal);
                if (!control.readBoolean()) throw new IOException("Verification phase was rejected");
                for (int from=0;from<candidates.size() && !host.stopping();from+=WINDOW_SIZE) {
                    List<File> window=candidates.subList(from,Math.min(candidates.size(),from+WINDOW_SIZE));
                    control.writeInt(WINDOW); writeFiles(window);
                    if (!control.readBoolean()) throw new IOException("Verification window was rejected");
                    try (HashBatch batch=new HashBatch(window,true)) {
                        Map<Integer,Hash> local=new HashMap<>();
                        Set<Integer> completed=new HashSet<>();
                        while (completed.size()<window.size() && !host.stopping()) {
                            host.checkCancelled();
                            List<Hash> ready=batch.drain();
                            for (Hash hash:ready) local.put(hash.id,hash);
                            control.writeInt(POLL); control.writeBoolean(host.stopping());
                            control.writeInt(ready.size());
                            for (Hash hash:ready) writeHash(hash);
                            if (control.readBoolean()) host.stop();
                            int results=count(window.size());
                            for (int i=0;i<results;i++) {
                                File done=readOutcome(local,true);
                                if (!window.contains(done) || !completed.add(done.id))
                                    throw new IOException("Repeated or out-of-window verification result");
                                concluded(done);
                            }
                            if (completed.size()<window.size() && !host.stopping()) {
                                progress(local.size()==window.size()?"waiting-peer":"verifying","");
                                // No mandatory pause when results are ready, especially for tiny files.
                                if (ready.isEmpty() && results==0) batch.awaitActivity();
                            }
                        }
                        // A stop may arrive between polls. Always carry it across the final boundary.
                        control.writeInt(WINDOW_END); control.writeBoolean(host.stopping());
                        if (control.readBoolean()) host.stop();
                    }
                }
                control.writeInt(ROUND_END);
                List<File> mismatches=readFiles(files.size());
                List<File> expected=matching(MISMATCH);
                if (!new HashSet<>(mismatches).equals(new HashSet<>(expected)))
                    throw new IOException("Peer retry set disagrees with verification results");
                if (host.stopping() || mismatches.isEmpty()) break;
                progress("retrying","");
                control.writeInt(RETRY); writeFiles(mismatches);
                for (File file:mismatches) {
                    host.checkCancelled();
                    file.retried=true;
                    progress(host.stopping()?"stopping":"retrying",file.path);
                    host.retry(file,true);
                }
                candidates=matching(PENDING);
            }
            finalizeSender();
        } finally { summarize(); }
    }

    private void finalizeSender() throws IOException {
        summary.verificationStopped=host.stopping();
        if (host.stopping()) progress("stopping","");
        for (int from=0;from<files.size();from+=WINDOW_SIZE) {
            control.writeInt(FINALIZE); control.writeBoolean(host.stopping());
            List<File> batch=files.subList(from,Math.min(files.size(),from+WINDOW_SIZE));
            writeFiles(batch);
            int results=count(WINDOW_SIZE);
            if (results!=batch.size()) throw new IOException("Incomplete final verification result set");
            Set<Integer> seen=new HashSet<>();
            for (int i=0;i<results;i++) {
                File file=readOutcome(Collections.emptyMap(),false);
                if (!batch.contains(file) || !seen.add(file.id)) throw new IOException("Invalid final verification result");
                if (file.state==UNVERIFIED) host.result(file.path,"unverified",file.message);
            }
        }
        control.writeInt(FINISH); control.writeBoolean(host.stopping());
        if (!control.readBoolean()) throw new IOException("Verification did not finish");
        if (control.readBoolean()) host.stop();
    }

    void receive() throws IOException {
        HashBatch batch=null;
        List<File> window=Collections.emptyList();
        Map<Integer,Hash> remote=new HashMap<>(),local=new HashMap<>();
        Set<Integer> reported=new HashSet<>();
        try {
            while (true) {
                host.checkCancelled();
                int command=control.readInt();
                if (command==START) {
                    int number=control.readInt(); long total=control.readLong();
                    if (batch!=null || number!=round+1 || total<0 || total>files.size())
                        throw new IOException("Invalid verification round");
                    round(number,total); control.writeBoolean(true);
                } else if (command==WINDOW) {
                    if (batch!=null) throw new IOException("Overlapping verification windows");
                    window=readFiles(WINDOW_SIZE);
                    for (File file:window) if (file.state!=PENDING) throw new IOException("File is not pending verification");
                    remote.clear(); local.clear(); reported.clear();
                    batch=new HashBatch(window,false); control.writeBoolean(true);
                } else if (command==POLL) {
                    if (batch==null) throw new IOException("No verification window");
                    if (control.readBoolean()) host.stop();
                    int hashes=count(WINDOW_SIZE);
                    for (int i=0;i<hashes;i++) {
                        Hash hash=readHash();
                        if (!window.contains(file(hash.id)) || remote.put(hash.id,hash)!=null)
                            throw new IOException("Repeated or out-of-window source hash");
                    }
                    for (Hash hash:batch.drain()) local.put(hash.id,hash);
                    List<File> ready=new ArrayList<>();
                    if (!host.stopping()) for (File file:window) {
                        Hash source=remote.get(file.id),target=local.get(file.id);
                        if (source==null || target==null || reported.contains(file.id)) continue;
                        compare(file,source,target);
                        reported.add(file.id); ready.add(file); concluded(file);
                    }
                    if (host.stopping()) progress("stopping","");
                    else if (local.size()==window.size() && reported.size()<window.size()) progress("waiting-peer","");
                    control.writeBoolean(host.stopping()); control.writeInt(ready.size());
                    for (File file:ready) writeOutcome(file,local.get(file.id).digest);
                } else if (command==WINDOW_END) {
                    if (batch==null) throw new IOException("No verification window to end");
                    if (control.readBoolean()) host.stop();
                    batch.close(); batch=null;
                    for (File file:window) if (file.state==PENDING) fs.releaseVerification(file.token);
                    control.writeBoolean(host.stopping());
                } else if (command==ROUND_END) {
                    if (batch!=null) throw new IOException("Unfinished verification window");
                    writeFiles(matching(MISMATCH));
                } else if (command==RETRY) {
                    if (batch!=null) throw new IOException("Retry during verification");
                    List<File> retry=readFiles(files.size());
                    for (File file:retry) if (file.state!=MISMATCH) throw new IOException("Only checksum mismatches may retry");
                    for (File file:retry) {
                        host.checkCancelled(); file.retried=true;
                        progress(host.stopping()?"stopping":"retrying",file.path);
                        host.retry(file,false);
                    }
                } else if (command==FINALIZE) {
                    if (batch!=null) throw new IOException("Finalize during hashing");
                    if (control.readBoolean()) host.stop();
                    List<File> finalFiles=readFiles(WINDOW_SIZE);
                    for (File file:finalFiles) finalizeFile(file);
                    control.writeInt(finalFiles.size());
                    for (File file:finalFiles) writeOutcome(file,"");
                } else if (command==FINISH) {
                    if (batch!=null) throw new IOException("Unfinished verification window");
                    if (control.readBoolean()) host.stop();
                    summary.verificationStopped=host.stopping();
                    control.writeBoolean(true); control.writeBoolean(host.stopping()); return;
                } else throw new IOException("Invalid verification command");
            }
        } finally {
            try {
                if (batch!=null) batch.close();
            } finally {
                for (File file:files) if (file.state!=VERIFIED && !file.retained) {
                    try { fs.releaseVerification(file.token); fs.retainUnverified(file.token); file.retained=true; }
                    catch (IOException ignored) { /* endSession also releases owned leases; preserve on failure. */ }
                }
                summarize();
            }
        }
    }

    private void compare(File file,Hash source,Hash target) {
        try {
            if (source.status!=HASH_OK || target.status!=HASH_OK) {
                file.state=FAILED;
                file.message=source.status!=HASH_OK?source.message:target.message;
                fs.releaseVerification(file.token);
            } else if (!source.digest.equals(target.digest)) {
                file.state=MISMATCH; file.message="Complete-file SHA-256 mismatch; scheduled for retry";
                fs.releaseVerification(file.token);
            } else {
                fs.completeVerification(file.token,source.digest,target.digest);
                file.state=VERIFIED; file.message="SHA-256 verified: "+file.target;
            }
        } catch (IOException e) {
            file.state=FAILED; file.message=shortText(e.toString());
            try { fs.releaseVerification(file.token); } catch (IOException ignored) { }
        }
    }
    private void finalizeFile(File file) {
        try {
            if (file.state==PENDING) {
                file.target=fs.publishUnverified(file.token);
                file.state=UNVERIFIED; file.message="Received without complete-file verification: "+file.target;
                host.result(file.path,"unverified",file.message);
            } else if (!file.retained && (file.state==MISMATCH || file.state==FAILED)) {
                fs.retainUnverified(file.token);
                file.retained=true;
            }
        } catch (IOException e) {
            file.state=FAILED; file.message=shortText(e.toString());
        }
    }
    private void summarize() {
        summary.verificationStopped=host.stopping();
        for (File file:files) {
            if (file.summarized) continue;
            file.summarized=true;
            if (file.retried) summary.retriedFiles++;
            if (file.state==VERIFIED) { summary.verifiedFiles++; summary.completedFiles++; }
            else if (file.state==FAILED || file.state==MISMATCH) {
                summary.verificationFailedFiles++; summary.failedFiles++;
                summary.failures.add(file.path+": "+file.message);
            } else summary.unverifiedFiles++;
        }
    }

    private final class HashBatch implements AutoCloseable {
        final Map<Integer,Future<Hash>> futures=new LinkedHashMap<>();
        final Set<Integer> drained=new HashSet<>();
        final Map<Integer,TransferFileAccess.Handle> handles=new ConcurrentHashMap<>();
        final AtomicBoolean stopping=new AtomicBoolean();
        final Semaphore activity=new Semaphore(0);
        final ExecutorService pool;
        HashBatch(List<File> files,boolean source) {
            pool=Executors.newFixedThreadPool(WORKERS,r -> {
                Thread thread=new Thread(r,"HFX-SHA256"); thread.setDaemon(true); return thread;
            });
            for (File file:files) futures.put(file.id,pool.submit(() -> {
                try { return hash(file,source); }
                finally { activity.release(); }
            }));
            pool.shutdown();
        }
        private Hash hash(File file,boolean source) {
            try {
                checkHashCancelled();
                if (source) validateSource(file);
                checkHashCancelled();
                try (TransferFileAccess.Handle handle=source?fs.openRead(file.source):fs.openSealedRead(file.token)) {
                    handles.put(file.id,handle);
                    checkHashCancelled();
                    String digest=TransferHashes.hash(handle.channel,file.size,() -> stopping.get() || host.stopping());
                    if (source) validateSource(file);
                    return new Hash(file.id,HASH_OK,digest,"");
                } finally { handles.remove(file.id); }
            } catch (SourceChanged e) { return new Hash(file.id,SOURCE_CHANGED,"",e.getMessage()); }
            catch (IOException | RuntimeException e) {
                return new Hash(file.id,stopping.get() || host.stopping()?HASH_CANCELLED:HASH_IO,"",e.toString());
            }
        }
        private void checkHashCancelled() throws IOException {
            if (stopping.get() || host.stopping() || Thread.currentThread().isInterrupted())
                throw new IOException("Verification stopped");
        }
        private void validateSource(File file) throws IOException {
            RemoteFile now=fs.stat(file.source);
            if (now.isDirectory() || now.getSize()!=file.size || now.lastModified()!=file.modified)
                throw new SourceChanged("Source changed after data transfer: "+file.path);
        }
        List<Hash> drain() throws IOException {
            List<Hash> ready=new ArrayList<>();
            for (Map.Entry<Integer,Future<Hash>> entry:futures.entrySet()) {
                if (drained.contains(entry.getKey()) || !entry.getValue().isDone()) continue;
                try { ready.add(entry.getValue().get()); }
                catch (CancellationException e) { ready.add(new Hash(entry.getKey(),HASH_CANCELLED,"","Verification stopped")); }
                catch (ExecutionException e) { ready.add(new Hash(entry.getKey(),HASH_IO,"",e.getCause().toString())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled",e); }
                drained.add(entry.getKey());
            }
            return ready;
        }
        void awaitActivity() throws IOException {
            try { activity.tryAcquire(50,TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled",e); }
        }
        @Override public void close() throws IOException {
            stopping.set(true);
            for (Future<Hash> future:futures.values()) if (!future.isDone()) future.cancel(true);
            for (TransferFileAccess.Handle handle:handles.values())
                try { handle.close(); } catch (IOException ignored) { }
            pool.shutdownNow();
            boolean interrupted=Thread.interrupted();
            boolean terminated=false;
            try { terminated=pool.awaitTermination(5,TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted=true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (!terminated) throw new IOException("Verification workers did not stop; connection cannot be reused safely");
        }
    }
    private static final class SourceChanged extends IOException {
        SourceChanged(String message) { super(message); }
    }
}
