package top.weixiansen574.hybridfilexfer.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/** Owns only .hfx-* staging files; user sources and pre-existing destination files survive failure. */
public abstract class LocalTransferFileAccess implements TransferFileAccess {
    private static final String MAGIC = "HybridFileXfer-stage-4";
    private static final String CLAIM_MAGIC = "HybridFileXfer-reservation-1";
    private final Map<String,Stage> stages = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private volatile boolean cancellationRequested;
    private String sessionId;
    private File sessionPath;
    private RandomAccessFile sessionFile;
    private FileLock sessionLock;
    @Override public synchronized void beginSession() { cancellationRequested=false; warnings.clear(); }
    // Binder cancellation must not wait for the monitor held by prepare/commit while hashing.
    @Override public void cancelPendingOperations() { cancellationRequested=true; }
    @Override public synchronized List<String> takeWarnings() {
        List<String> result=new ArrayList<>(warnings);
        warnings.clear();
        return result;
    }
    private void warning(String message) {
        if (warnings.size()<100) warnings.add(message.length()>1024?message.substring(0,1024):message);
        else warnings.set(99,"Additional filesystem warnings were omitted.");
    }
    private static final class Stage {
        File root, target, part, meta, lockPath, claim, previous;
        String identity, hash, id, token, sealedIdentity = "", state = "WRITING";
        long size, modified, offset, generation, sealedModified;
        boolean overwrite, claimed;
        int readers, writers;
        final Set<Closeable> handles=new HashSet<>();
        final Map<String,Boolean> externalHandles=new HashMap<>();
        boolean retiring;
        RandomAccessFile lockFile;
        FileLock lock;
    }

    @Override public RemoteFile stat(String path) throws IOException {
        File file = checkedSource(path);
        if (!file.exists()) throw new FileNotFoundException(path);
        if (!file.isFile() && !file.isDirectory()) throw new IOException("Not a regular file or directory: " + path);
        return new RemoteFile(file);
    }
    @Override public List<RemoteFile> list(String path) throws IOException {
        File dir = checkedSource(path);
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot list directory: " + path);
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<RemoteFile> result = new ArrayList<>();
        for (File file : files) result.add(new RemoteFile(file));
        return result;
    }
    private static File checkedSource(String path) throws IOException { return new File(path).getAbsoluteFile(); }
    protected File physicalPath(File file) throws IOException { return file.getCanonicalFile(); }
    protected String reservationKey(File file) throws IOException { return file.getCanonicalPath(); }
    /** An inode identity where available; platform adapters document any fallback. */
    protected abstract String fileIdentity(File file) throws IOException;
    @Override public String canonicalPath(String path) throws IOException { return physicalPath(new File(path)).getPath(); }
    @Override public Handle openRead(String path) throws IOException {
        File file = checkedSource(path);
        if (!file.isFile()) throw new IOException("Not a readable regular file: " + path);
        FileInputStream stream = new FileInputStream(file);
        return new Handle(stream.getChannel(), stream);
    }
    protected File resolve(File root, String relative) throws IOException {
        TransferPaths.validateRelative(relative);
        File target = new File(root, relative.replace('/', File.separatorChar)).getAbsoluteFile();
        File canonical = physicalPath(target);
        String boundary = physicalPath(root).getPath();
        if (!boundary.endsWith(File.separator)) boundary += File.separator;
        if (!canonical.getPath().startsWith(boundary) || !canonical.equals(target))
            throw new IOException("Destination escapes the selected directory or follows a symbolic link");
        return target;
    }
    private File root(String path) throws IOException {
        File root = physicalPath(new File(path));
        if (!root.isDirectory()) throw new IOException("Destination directory does not exist: " + root);
        return root;
    }
    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create directory: " + dir);
    }
    @Override public void createDirectory(String root, String relative, long modified) throws IOException {
        File target = resolve(root(root), relative);
        if (target.exists() && !target.isDirectory()) throw new IOException("A file occupies the directory name: " + target);
        mkdirs(target);
        if (modified > 0) target.setLastModified(modified);
    }
    @Override public synchronized Prepared prepare(String rootPath, String relative, String identity, long size,
                                                   long modified, String hash, TransferOptions options) throws IOException {
        options.validate();
        if (cancellationRequested) throw new IOException("Cancelled");
        if (size < 0 || identity == null || !identity.matches("[0-9a-f]{64}")
                || (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")))
            throw new IOException("Invalid file metadata");
        File root = root(rootPath), target = resolve(root, relative);
        mkdirs(target.getParentFile());
        // Recheck after directory creation; never follow an existing target symlink.
        target = resolve(root, relative);
        Prepared prepared = new Prepared();
        prepared.target = target.getPath();
        if (target.exists()) {
            if (!target.isFile()) throw new IOException("A directory occupies the file name: " + target);
            if (options.incremental && !hash.isEmpty() && target.length() == size
                    && hash.equals(hashLocal(target, size))) {
                prepared.status = "skipped";
                prepared.message = "Existing file has identical SHA-256 content";
                return prepared;
            }
            if (options.conflictPolicy == TransferOptions.ConflictPolicy.SKIP) {
                prepared.status = "skipped";
                prepared.message = "Existing file preserved by skip policy; content was not assumed identical";
                return prepared;
            }
            if (options.conflictPolicy == TransferOptions.ConflictPolicy.KEEP_BOTH) {
                File original = target;
                for (int n=1; target.exists(); n++) {
                    if (n>100000) throw new IOException("Too many conflicting destination names");
                    String name = original.getName();
                    int dot = name.lastIndexOf('.');
                    String suffix = " (" + n + ")";
                    String candidate = dot > 0 ? name.substring(0,dot)+suffix+name.substring(dot) : name+suffix;
                    target = new File(original.getParentFile(), candidate);
                }
                prepared.target = target.getPath();
            }
        }
        String id = TransferHashes.text(target.getCanonicalPath() + "\n" + identity);
        Stage s = new Stage();
        s.root=root; s.target=target; s.identity=identity; s.hash=hash; s.size=size; s.modified=modified;
        s.id=id; s.token=UUID.randomUUID().toString();
        s.overwrite=options.conflictPolicy==TransferOptions.ConflictPolicy.OVERWRITE;
        s.part=new File(target.getParentFile(), ".hfx-"+id+".part");
        s.meta=new File(target.getParentFile(), ".hfx-"+id+".meta");
        s.lockPath=new File(target.getParentFile(), ".hfx-"+TransferHashes.text(reservationKey(target))+".lock");
        s.claim=new File(s.lockPath.getParentFile(),s.lockPath.getName()+".claim");
        assertOwnedPath(s.part); assertOwnedPath(s.meta);
        acquire(s);
        try {
            checkExistingReservation(s);
            if (s.meta.exists()) {
                Properties p=load(s.meta);
                if (!MAGIC.equals(p.getProperty("magic")) || !identity.equals(p.getProperty("identity"))
                        || !Long.toString(size).equals(p.getProperty("size"))
                        || !Long.toString(modified).equals(p.getProperty("modified"))
                        || !hash.equals(p.getProperty("hash")))
                    throw new IOException("Staging metadata does not match; existing files were preserved");
                try { s.offset=Long.parseLong(p.getProperty("offset")); }
                catch (RuntimeException e) { throw new IOException("Invalid staging checkpoint",e); }
                if (s.offset<0 || s.offset>size)
                    throw new IOException("Damaged staging checkpoint; existing files were preserved");
                s.generation=parseLong(p,"generation",0);
                s.state=p.getProperty("state","WRITING");
                if (!Arrays.asList("WRITING","SEALED","RETAINED").contains(s.state))
                    throw new IOException("Invalid staging state");
                s.part=ownedPart(s,p.getProperty("part",s.part.getName()));
                String previous=p.getProperty("previous","");
                if (!previous.isEmpty()) s.previous=ownedPart(s,previous);
                s.sealedIdentity=p.getProperty("sealedIdentity","");
                s.sealedModified=parseLong(p,"sealedModified",0);
                if (!s.part.exists()) {
                    // Publication can succeed while metadata cleanup fails (for example,
                    // a Windows handle denies deletion). Never touch the completed target.
                    // Reset metadata before creating the new part, so another failed
                    // checkpoint replacement cannot leave a zero-byte part with an old offset.
                    s.offset=0;
                    s.state="WRITING"; s.sealedIdentity="";
                    save(s);
                    if (!s.part.createNewFile()) throw new IOException("Cannot recreate staging file");
                } else if (!s.part.isFile() || s.part.length()<s.offset) {
                    throw new IOException("Damaged staging checkpoint; existing files were preserved");
                } else if (s.target.exists() && sameFile(s.part,s.target)) {
                    // A link-based no-replace publication can leave its source behind.
                    // If metadata cleanup also failed, truncating that part would truncate
                    // the successfully published target through the shared inode.
                    throw new IOException("Partial file is linked to the published destination; both were preserved");
                }
            } else {
                if (s.part.exists()) throw new IOException("Unrecognized partial file; preserved for inspection");
                if (!s.part.createNewFile()) throw new IOException("Cannot create staging file");
            }
            if (!options.resume && !"WRITING".equals(s.state) && s.offset==s.size) {
                // A completed, unchecked generation is evidence, even when resume is disabled.
                newGeneration(s);
            } else {
                if (!options.resume) {
                    s.offset=0;
                    // Persist the new prefix before truncating the old one.
                    save(s);
                }
                s.state="WRITING"; s.sealedIdentity="";
                try (RandomAccessFile file=new RandomAccessFile(s.part,"rw")) {
                    file.setLength(s.offset); file.getChannel().force(true);
                }
            }
            save(s);
            prepared.offset=s.offset;
            prepared.prefixHash=hashLocal(s.part,s.offset);
            prepared.token=s.token;
            stages.put(prepared.token,s);
            return prepared;
        } catch (IOException | RuntimeException e) {
            try { clearReservation(s); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            try { release(s); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            closeIdleSession();
            throw e;
        }
    }
    private void assertOwnedPath(File file) throws IOException {
        if (!physicalPath(file).equals(file.getAbsoluteFile())) throw new IOException("Staging path is a symbolic link");
    }
    public synchronized String stagingPath(String token) throws IOException { return stage(token).part.getPath(); }
    /** Used by the privileged Binder adapter before granting a writable descriptor. */
    public synchronized String writableStagingPath(String token) throws IOException {
        Stage s=stage(token); requireWriting(s); return s.part.getPath();
    }
    /** A Binder caller must acknowledge closing this exact descriptor, including failed opens. */
    public synchronized String reserveExternalHandle(String token,String handleId,boolean reader) throws IOException {
        Stage s=stage(token);
        if (handleId==null || !handleId.matches("[0-9a-f-]{36}") || s.externalHandles.containsKey(handleId))
            throw new IOException("Invalid external descriptor identifier");
        String path=reader?sealedStagingPath(token):writableStagingPath(token);
        s.externalHandles.put(handleId,reader);
        if (reader) s.readers++; else s.writers++;
        return path;
    }
    public synchronized void closeExternalHandle(String token,String handleId) throws IOException {
        Stage s=stages.get(token);
        if (s==null) return;
        Boolean reader=s.externalHandles.remove(handleId);
        if (reader==null) return;
        if (reader) s.readers--; else s.writers--;
        if (s.retiring && s.readers==0 && s.writers==0) {
            s.retiring=false;
            abandon(token,true);
        }
    }
    @Override public synchronized Handle openStage(String token) throws IOException {
        Stage s=stage(token); requireWriting(s);
        RandomAccessFile file=new RandomAccessFile(s.part,"rw");
        s.writers++;
        return trackedHandle(s,file.getChannel(),file,false);
    }
    @Override public synchronized void checkpoint(String token,long offset) throws IOException {
        Stage s=stage(token);
        requireWriting(s);
        if (offset<s.offset || offset>s.size || offset>s.part.length()) throw new IOException("Invalid durable prefix");
        s.offset=offset;
        save(s);
    }
    @Override public synchronized void commit(String token,boolean verifySha256) throws IOException {
        Stage s=stage(token);
        requireWriting(s);
        if (s.writers!=0) throw new IOException("Close staging writers before publication");
        if (s.offset!=s.size || s.part.length()!=s.size) throw new IOException("Incomplete file cannot be committed");
        assertOwnedPath(s.part); assertOwnedPath(s.target);
        if (verifySha256 && (s.hash.isEmpty() || !s.hash.equals(hashLocal(s.part,s.size))))
            throw new IOException("Final SHA-256 verification failed; partial file retained");
        if (cancellationRequested || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        publish(s,s.target,s.overwrite);
    }
    private void publish(Stage s,File target,boolean overwrite) throws IOException {
        assertOwnedPath(s.part); assertOwnedPath(target);
        if (!overwrite && target.exists()) throw new IOException("Destination appeared during transfer; preserved");
        if (s.modified>0 && !s.part.setLastModified(s.modified)) throw new IOException("Cannot preserve modification time");
        atomicMove(s.part,target,overwrite);
        stages.remove(s.token);
        // The final file is now published. Cleanup errors must never reverse that outcome.
        try {
            if (s.part.exists())
                warning("File committed: "+s.target+"; published staging file could not be removed: "+s.part);
        } catch (SecurityException e) {
            warning("File committed: "+s.target+"; could not inspect published staging file cleanup: "+e);
        }
        try {
            if (!s.meta.delete() && s.meta.exists())
                warning("File committed: "+s.target+"; could not remove staging metadata: "+s.meta);
        } catch (SecurityException e) {
            warning("File committed: "+s.target+"; staging metadata cleanup was denied: "+e);
        } finally {
            if (s.previous!=null) cleanupOwned(s.previous,"previous staging file");
            try { clearReservation(s); }
            catch (IOException | SecurityException e) { warning("File committed; could not clear its reservation: "+e); }
            try { release(s); }
            catch (IOException | SecurityException e) {
                warning("File committed: "+s.target+"; could not release the staging lock cleanly: "+e);
            }
            closeIdleSession();
        }
    }
    @Override public synchronized void abandon(String token,boolean keep) throws IOException {
        Stage s=stages.get(token);
        if (s==null) return;
        if (s.writers!=0 || s.readers!=0) throw new IOException("Close staging descriptors before releasing the file");
        reacquire(s);
        try {
            // Sealed data must survive verification cancellation, independently of resume.
            if (!keep && "WRITING".equals(s.state)) {
                if (!s.part.delete() && s.part.exists()) throw new IOException("Cannot remove partial file");
                if (s.previous==null) {
                    if (!s.meta.delete() && s.meta.exists()) throw new IOException("Cannot remove partial metadata");
                } else {
                    s.part=s.previous; s.previous=null; s.offset=s.size; s.state="RETAINED";
                    s.sealedIdentity=fileIdentity(s.part); s.sealedModified=s.part.lastModified(); save(s);
                }
            }
        } finally {
            stages.remove(token);
            try { clearReservation(s); } finally { release(s); closeIdleSession(); }
        }
    }
    @Override public synchronized void seal(String token) throws IOException {
        Stage s=stage(token);
        requireWriting(s);
        if (s.writers!=0) throw new IOException("Close staging writers before sealing");
        if (s.offset!=s.size || s.part.length()!=s.size) throw new IOException("Incomplete file cannot be sealed");
        // Also covers a remote writer whose descriptor is owned by the Binder caller.
        try (RandomAccessFile file=new RandomAccessFile(s.part,"rw")) { file.getChannel().force(true); }
        s.sealedIdentity=fileIdentity(s.part);
        s.sealedModified=s.part.lastModified();
        s.state="SEALED";
        save(s);
        // CRC-only transfers keep their original target lock until publication.
        // Persist a session claim only when deferred verification releases that lock.
        reserve(s);
        release(s);
    }
    /** Reserves the generation before the Binder adapter opens a read-only descriptor. */
    public synchronized String sealedStagingPath(String token) throws IOException {
        Stage s=stage(token);
        if (!"SEALED".equals(s.state)) throw new IOException("File is not sealed for verification");
        reacquire(s);
        try {
            validateSealed(s);
            s.state="VERIFYING";
            return s.part.getPath();
        } catch (IOException | RuntimeException e) {
            release(s);
            throw e;
        }
    }
    @Override public synchronized Handle openSealedRead(String token) throws IOException {
        Stage s=stage(token);
        sealedStagingPath(token);
        FileInputStream in=null;
        try {
            in=new FileInputStream(s.part);
            // Detect a path replacement between identity validation and opening.
            validateSealed(s);
            s.readers++;
            return trackedHandle(s,in.getChannel(),in,true);
        } catch (IOException | RuntimeException e) {
            if (in!=null) try { in.close(); } catch (IOException close) { e.addSuppressed(close); }
            s.state="SEALED"; release(s); throw e;
        }
    }
    @Override public synchronized void completeVerification(String token,String expected,String actual) throws IOException {
        Stage s=stage(token);
        if (!"VERIFYING".equals(s.state) || s.lock==null || !s.lock.isValid())
            throw new IOException("No active verification reservation");
        if (s.readers!=0) throw new IOException("Close verification readers before publication");
        if (expected==null || !expected.matches("[0-9a-f]{64}") || !expected.equals(actual)
                || (!s.hash.isEmpty() && !s.hash.equals(expected)))
            throw new IOException("Final SHA-256 verification failed; complete file retained");
        assertReservation(s);
        validateSealed(s);
        if (cancellationRequested || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        publish(s,s.target,s.overwrite);
    }
    @Override public synchronized void releaseVerification(String token) throws IOException {
        Stage s=stage(token);
        if (s.readers!=0) throw new IOException("Close verification readers before releasing the reservation");
        if ("VERIFYING".equals(s.state)) s.state="SEALED";
        if (!"SEALED".equals(s.state)) throw new IOException("No sealed verification reservation");
        release(s);
    }
    @Override public synchronized void resetForRetry(String token) throws IOException {
        Stage s=stage(token);
        if (!"SEALED".equals(s.state) || s.readers!=0 || s.writers!=0)
            throw new IOException("Release verification before retrying");
        reacquire(s);
        try {
            validateSealed(s);
            newGeneration(s);
        } catch (IOException | RuntimeException e) {
            release(s);
            throw e;
        }
    }
    @Override public synchronized String publishUnverified(String token) throws IOException {
        Stage s=stage(token);
        if (!"SEALED".equals(s.state) || s.readers!=0 || s.writers!=0)
            throw new IOException("Only a closed, complete sealed file can be published without verification");
        reacquire(s);
        validateSealed(s);
        File destination=s.target;
        if (destination.exists()) {
            String name=s.target.getName();
            int dot=name.lastIndexOf('.');
            for (int n=1;;n++) {
                if (n>100000) throw new IOException("Too many unverified destination names");
                String suffix=" (unverified "+n+")";
                destination=new File(s.target.getParentFile(),
                        dot>0?name.substring(0,dot)+suffix+name.substring(dot):name+suffix);
                assertOwnedPath(destination);
                if (destination.exists()) continue;
                Stage output=new Stage();
                output.lockPath=new File(destination.getParentFile(),
                        ".hfx-"+TransferHashes.text(reservationKey(destination))+".lock");
                output.claim=new File(output.lockPath.getParentFile(),output.lockPath.getName()+".claim");
                acquire(output);
                try {
                    assertOwnedPath(output.claim);
                    if (output.claim.exists()) {
                        Properties claim=load(output.claim);
                        if (!CLAIM_MAGIC.equals(claim.getProperty("magic")))
                            throw new IOException("Unrecognized output reservation; preserved");
                        if (leaseAlive(claim)) continue;
                    }
                    if (destination.exists()) continue;
                    publish(s,destination,false);
                    return destination.getPath();
                } finally { release(output); }
            }
        }
        // Even OVERWRITE never replaces an original with data the user stopped checking.
        publish(s,destination,false);
        return destination.getPath();
    }
    @Override public synchronized void retainUnverified(String token) throws IOException {
        Stage s=stage(token);
        if (s.writers!=0 || s.readers!=0) throw new IOException("Close descriptors before retaining the file");
        reacquire(s);
        if ("VERIFYING".equals(s.state)) s.state="SEALED";
        if ("SEALED".equals(s.state)) { s.state="RETAINED"; save(s); }
        abandon(token,true);
    }
    @Override public synchronized void endSession() throws IOException {
        IOException failure=null;
        for (String token:new ArrayList<>(stages.keySet())) {
            try {
                Stage s=stages.get(token);
                for (Closeable handle:new ArrayList<>(s.handles)) handle.close();
                if (!s.externalHandles.isEmpty()) {
                    // A Binder open may still be returning a duplicated descriptor.
                    // Keep its reservation until the client's stale-epoch close arrives.
                    s.retiring=true;
                    continue;
                }
                abandon(token,true);
            }
            catch (IOException e) {
                if (failure==null) failure=e; else failure.addSuppressed(e);
                // A failed checkpoint/claim must not keep this process's lease alive.
                Stage s=stages.remove(token);
                if (s!=null) try { release(s); } catch (IOException close) { failure.addSuppressed(close); }
            }
        }
        closeIdleSession();
        if (failure!=null) throw failure;
    }
    private Handle trackedHandle(Stage s,FileChannel channel,Closeable owner,boolean reader) {
        Closeable tracked=new Closeable() {
            private boolean closed;
            @Override public void close() throws IOException {
                synchronized (LocalTransferFileAccess.this) {
                    if (closed) return;
                    closed=true;
                    try { owner.close(); }
                    finally {
                        if (reader) s.readers--; else s.writers--;
                        s.handles.remove(this);
                    }
                }
            }
        };
        s.handles.add(tracked);
        return new Handle(channel,tracked);
    }
    private void requireWriting(Stage s) throws IOException {
        if (!"WRITING".equals(s.state)) throw new IOException("Sealed files cannot be reopened for writing");
        if (s.lock==null || !s.lock.isValid()) throw new IOException("Missing staging lock");
        assertReservation(s); assertOwnedPath(s.part);
        if (s.target.exists() && sameFile(s.part,s.target))
            throw new IOException("Staging file is linked to the published destination");
    }
    private void validateSealed(Stage s) throws IOException {
        assertOwnedPath(s.part); assertOwnedPath(s.target);
        if (s.offset!=s.size || !s.part.isFile() || s.part.length()!=s.size
                || !s.sealedIdentity.equals(fileIdentity(s.part)) || s.sealedModified!=s.part.lastModified())
            throw new IOException("Sealed staging file changed; existing files were preserved");
        if (s.target.exists() && sameFile(s.part,s.target))
            throw new IOException("Staging file is linked to a published destination");
    }
    private void newGeneration(Stage s) throws IOException {
        File oldPart=s.part,oldPrevious=s.previous;
        long oldOffset=s.offset,oldGeneration=s.generation,oldModified=s.sealedModified;
        String oldState=s.state,oldIdentity=s.sealedIdentity;
        File next=new File(s.part.getParentFile(),".hfx-"+s.id+"."+UUID.randomUUID()+".part");
        assertOwnedPath(next);
        if (!next.createNewFile()) throw new IOException("Cannot create retry staging file");
        s.part=next; s.previous=oldPart; s.offset=0; s.generation++;
        s.state="WRITING"; s.sealedIdentity=""; s.sealedModified=0;
        try { save(s); }
        catch (IOException | RuntimeException e) {
            s.part=oldPart; s.previous=oldPrevious; s.offset=oldOffset; s.generation=oldGeneration;
            s.state=oldState; s.sealedIdentity=oldIdentity; s.sealedModified=oldModified;
            if (!next.delete() && next.exists()) warning("Could not remove unused retry stage: "+next);
            throw e;
        }
        // A newer complete copy exists. Keep at most one previous complete generation.
        if (oldPrevious!=null && !oldPrevious.equals(oldPart)) cleanupOwned(oldPrevious,"superseded staging file");
    }
    private File ownedPart(Stage s,String name) throws IOException {
        if (!(name.equals(".hfx-"+s.id+".part")
                || name.matches("\\.hfx-"+s.id+"\\.[0-9a-f-]{36}\\.part")))
            throw new IOException("Invalid staging filename");
        File part=new File(s.target.getParentFile(),name);
        assertOwnedPath(part);
        return part;
    }
    private void acquire(Stage s) throws IOException {
        assertOwnedPath(s.lockPath);
        s.lockFile=new RandomAccessFile(s.lockPath,"rw");
        try {
            try { s.lock=s.lockFile.getChannel().tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException e) {
                throw new IOException("Destination is already being transferred",e);
            }
            if (s.lock==null) throw new IOException("Destination is already being transferred");
        } catch (IOException | RuntimeException e) { release(s); throw e; }
    }
    private void reacquire(Stage s) throws IOException {
        boolean acquired=s.lock==null || !s.lock.isValid();
        if (acquired) acquire(s);
        try { assertReservation(s); }
        catch (IOException | RuntimeException e) { if (acquired) release(s); throw e; }
    }
    private void ensureSession(File root) throws IOException {
        if (sessionLock!=null && sessionLock.isValid()) return;
        sessionId=UUID.randomUUID().toString();
        sessionPath=new File(root,".hfx-session-"+sessionId+".lease");
        if (!sessionPath.createNewFile()) throw new IOException("Cannot create transfer session lease");
        sessionFile=new RandomAccessFile(sessionPath,"rw");
        try {
            sessionLock=sessionFile.getChannel().tryLock();
            if (sessionLock==null) throw new IOException("Cannot reserve transfer session");
        } catch (IOException | RuntimeException e) {
            sessionFile.close(); sessionFile=null; sessionPath.delete(); throw e;
        }
    }
    private void checkExistingReservation(Stage s) throws IOException {
        assertOwnedPath(s.claim);
        if (s.claim.exists()) {
            Properties existing=load(s.claim);
            if (!CLAIM_MAGIC.equals(existing.getProperty("magic")))
                throw new IOException("Unrecognized staging reservation; preserved");
            if (leaseAlive(existing)) throw new IOException("Destination is reserved by another transfer session");
        }
    }
    private void reserve(Stage s) throws IOException {
        if (s.claimed) { assertReservation(s); return; }
        checkExistingReservation(s);
        ensureSession(s.root);
        Properties claim=new Properties();
        claim.setProperty("magic",CLAIM_MAGIC); claim.setProperty("session",sessionId);
        claim.setProperty("lease",sessionPath.getPath()); claim.setProperty("token",s.token);
        claim.setProperty("id",s.id);
        store(claim,s.claim,"HFX target reservation");
        s.claimed=true;
    }
    private boolean leaseAlive(Properties p) throws IOException {
        String owner=p.getProperty("session","");
        if (!owner.matches("[0-9a-f-]{36}")) throw new IOException("Invalid session reservation");
        File lease=new File(p.getProperty("lease","")).getAbsoluteFile();
        if (!lease.getName().equals(".hfx-session-"+owner+".lease"))
            throw new IOException("Invalid session lease path");
        assertOwnedPath(lease);
        if (!lease.exists()) return false;
        if (!lease.isFile()) throw new IOException("Invalid session lease file");
        try (RandomAccessFile file=new RandomAccessFile(lease,"rw")) {
            try (FileLock lock=file.getChannel().tryLock()) { return lock==null; }
            catch (java.nio.channels.OverlappingFileLockException e) { return true; }
        }
    }
    private void assertReservation(Stage s) throws IOException {
        assertOwnedPath(s.meta);
        if (s.claimed) {
            assertOwnedPath(s.claim);
            Properties p=load(s.claim);
            if (!CLAIM_MAGIC.equals(p.getProperty("magic")) || !Objects.equals(sessionId,p.getProperty("session"))
                    || !s.token.equals(p.getProperty("token")) || !s.id.equals(p.getProperty("id")))
                throw new IOException("Staging reservation changed; existing files were preserved");
        } else checkExistingReservation(s);
        if (s.meta.exists()) {
            Properties meta=load(s.meta);
            if (!MAGIC.equals(meta.getProperty("magic")) || !s.identity.equals(meta.getProperty("identity"))
                    || parseLong(meta,"generation",0)!=s.generation
                    || !s.part.getName().equals(meta.getProperty("part",".hfx-"+s.id+".part")))
                throw new IOException("Staging generation changed; existing files were preserved");
        }
    }
    private void clearReservation(Stage s) throws IOException {
        if (!s.claimed) return;
        assertOwnedPath(s.claim);
        if (!s.claim.exists()) return;
        Properties p=load(s.claim);
        if (Objects.equals(sessionId,p.getProperty("session")) && s.token.equals(p.getProperty("token"))
                && !s.claim.delete() && s.claim.exists()) throw new IOException("Cannot remove staging reservation");
        s.claimed=false;
    }
    private void closeIdleSession() {
        if (!stages.isEmpty() || sessionFile==null) return;
        try {
            try { if (sessionLock!=null && sessionLock.isValid()) sessionLock.release(); }
            finally { sessionFile.close(); }
            if (sessionPath!=null && !sessionPath.delete() && sessionPath.exists())
                warning("Could not remove completed session lease: "+sessionPath);
        } catch (IOException | SecurityException e) { warning("Could not close transfer session lease: "+e); }
        finally { sessionFile=null; sessionLock=null; sessionPath=null; sessionId=null; }
    }
    private void cleanupOwned(File file,String description) {
        try {
            assertOwnedPath(file);
            if (!file.delete() && file.exists()) warning("Could not remove "+description+": "+file);
        } catch (IOException | SecurityException e) { warning("Could not remove "+description+": "+e); }
    }
    private static long parseLong(Properties p,String key,long fallback) throws IOException {
        try { return Long.parseLong(p.getProperty(key,Long.toString(fallback))); }
        catch (NumberFormatException e) { throw new IOException("Invalid staging "+key,e); }
    }
    private Stage stage(String token) throws IOException {
        Stage s=stages.get(token);
        if (s==null) throw new IOException("Unknown staging token");
        if (s.retiring) throw new IOException("Staging session is closing");
        return s;
    }
    private String hashLocal(File file,long length) throws IOException {
        try (FileInputStream in=new FileInputStream(file)) {
            return TransferHashes.hash(in.getChannel(),length,() -> cancellationRequested);
        }
    }
    private static Properties load(File file) throws IOException {
        if (file.length()>16384) throw new IOException("Oversized staging metadata");
        Properties p=new Properties();
        try (InputStream in=new FileInputStream(file)) { p.load(in); }
        return p;
    }
    private void save(Stage s) throws IOException {
        Properties p=new Properties();
        p.setProperty("magic",MAGIC); p.setProperty("identity",s.identity);
        p.setProperty("size",Long.toString(s.size)); p.setProperty("modified",Long.toString(s.modified));
        p.setProperty("hash",s.hash); p.setProperty("offset",Long.toString(s.offset));
        p.setProperty("generation",Long.toString(s.generation)); p.setProperty("state",s.state);
        p.setProperty("part",s.part.getName()); p.setProperty("previous",s.previous==null?"":s.previous.getName());
        p.setProperty("sealedIdentity",s.sealedIdentity); p.setProperty("sealedModified",Long.toString(s.sealedModified));
        store(p,s.meta,"HFX durable prefix");
    }
    private void store(Properties p,File destination,String description) throws IOException {
        File temporary=new File(destination.getParentFile(),destination.getName()+"."+UUID.randomUUID()+".new");
        try {
            if (!temporary.createNewFile()) throw new IOException("Cannot create checkpoint");
            try (FileOutputStream out=new FileOutputStream(temporary)) { p.store(out,description); out.getChannel().force(true); }
            atomicMove(temporary,destination,true);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    protected abstract boolean sameFile(File first,File second) throws IOException;
    protected abstract void atomicMove(File source,File target,boolean replace) throws IOException;
    private static void release(Stage s) throws IOException {
        try { if (s.lock!=null && s.lock.isValid()) s.lock.release(); }
        finally {
            try { if (s.lockFile!=null) s.lockFile.close(); }
            finally { s.lock=null; s.lockFile=null; }
        }
        // Keep the tiny lock inode: deleting it allows two processes to lock different inodes.
    }
}
