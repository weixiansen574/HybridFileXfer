import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import top.weixiansen574.hybridfilexfer.core.*;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkTransferFileAccess;

/** Local generated-file checks for deferred verification reservations and preservation. */
public final class StagingVerificationTest {
    private static int checks;
    private static final byte[] DATA={3,5,7,11,13}, OLD={17,19,23};
    private interface Operation { void run() throws Exception; }
    private static void check(boolean value,String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void rejects(Operation action,String message) throws Exception {
        boolean rejected=false;
        try { action.run(); } catch (IOException expected) { rejected=true; }
        check(rejected,message);
    }
    private static TransferOptions options(boolean resume) {
        TransferOptions o=new TransferOptions();
        o.conflictPolicy=TransferOptions.ConflictPolicy.OVERWRITE; o.resume=resume;
        return o;
    }
    private static String hash(byte[] data) {
        StringBuilder out=new StringBuilder();
        for (byte b:TransferHashes.digest().digest(data)) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static TransferFileAccess.Prepared prepare(JdkTransferFileAccess fs,Path dir,String name,boolean resume)
            throws IOException {
        return fs.prepare(dir.toString(),name,TransferHashes.text(name),DATA.length,1000,"",options(resume));
    }
    private static void fill(JdkTransferFileAccess fs,String token,byte[] data) throws IOException {
        try (TransferFileAccess.Handle handle=fs.openStage(token)) {
            ByteBuffer bytes=ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) handle.channel.write(bytes);
            handle.channel.force(true);
        }
        fs.checkpoint(token,data.length);
    }
    private static String readHash(JdkTransferFileAccess fs,String token) throws IOException {
        try (TransferFileAccess.Handle handle=fs.openSealedRead(token)) {
            return TransferHashes.hash(handle.channel,DATA.length);
        }
    }
    private static void cleanup(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for (Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    private static Path sibling(Path part,String suffix) {
        String name=part.getFileName().toString();
        return part.resolveSibling(name.substring(0,name.length()-5)+suffix);
    }
    private static List<Path> entries(Path dir,String suffix) throws IOException {
        List<Path> paths=new ArrayList<>();
        try (java.util.stream.Stream<Path> stream=Files.list(dir)) {
            stream.filter(p->p.getFileName().toString().endsWith(suffix)).forEach(paths::add);
        }
        return paths;
    }
    private static void deferredAndReadOnly() throws Exception {
        Path dir=Files.createTempDirectory("hfx-seal-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess(), competing=new JdkTransferFileAccess();
        try {
            Files.write(dir.resolve("file.bin"),OLD);
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Path part=java.nio.file.Paths.get(fs.stagingPath(p.token));
            check(Arrays.equals(OLD,Files.readAllBytes(dir.resolve("file.bin"))),"Sealing preserves the old target");
            rejects(()->fs.openStage(p.token),"A sealed generation cannot reopen for writing");
            rejects(()->competing.prepare(dir.toString(),"file.bin",TransferHashes.text("different identity"),
                    DATA.length,1000,"",options(false)),"A different identity cannot bypass a live target claim");
            if (File.separatorChar=='\\') {
                rejects(()->competing.prepare(dir.toString(),"FILE.BIN",TransferHashes.text("different case"),
                        DATA.length,1000,"",options(false)),"Windows filename aliases share the same reservation");
            }
            check(Arrays.equals(DATA,Files.readAllBytes(part)),"Rejected competing prepare cannot truncate sealed bytes");
            TransferFileAccess.Handle reader=fs.openSealedRead(p.token);
            boolean readonly=false;
            try { reader.channel.write(ByteBuffer.wrap(new byte[]{1})); }
            catch (java.nio.channels.NonWritableChannelException expected) { readonly=true; }
            check(readonly,"Verification descriptor is read-only");
            rejects(()->fs.completeVerification(p.token,hash(DATA),hash(DATA)),"Publication requires closed readers");
            reader.close();
            rejects(()->fs.completeVerification(p.token,hash(DATA),hash(OLD)),"Mismatched hashes cannot publish");
            fs.releaseVerification(p.token);
            check(!Files.exists(dir.resolve("file (unverified 1).bin")),"A mismatch is not published as unchecked success");
            check(readHash(fs,p.token).equals(hash(DATA)),"Reopened sealed content hashes correctly");
            fs.completeVerification(p.token,hash(DATA),hash(DATA));
            check(Arrays.equals(DATA,Files.readAllBytes(dir.resolve("file.bin"))),"Verified publication applies overwrite policy");
            check(entries(dir,".claim").isEmpty() && entries(dir,".lease").isEmpty(),"Publication releases claim and session lease");
        } finally { fs.endSession(); competing.endSession(); cleanup(dir); }
    }
    private static void boundedDescriptors() throws Exception {
        Path dir=Files.createTempDirectory("hfx-many-sealed-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            for (int i=0;i<128;i++) {
                TransferFileAccess.Prepared p=prepare(fs,dir,"file-"+i+".bin",false);
                fill(fs,p.token,DATA); fs.seal(p.token);
            }
            List<Path> locks=entries(dir,".lock");
            check(locks.size()==128 && entries(dir,".lease").size()==1,"Many sealed files share one session lease");
            for (Path lock:locks) {
                try (RandomAccessFile f=new RandomAccessFile(lock.toFile(),"rw");
                     FileLock acquired=f.getChannel().tryLock()) {
                    check(acquired!=null,"Sealed stages release their per-file lock descriptor");
                }
            }
            fs.endSession();
            check(entries(dir,".lease").isEmpty() && entries(dir,".claim").isEmpty(),"Session shutdown releases all reservations");
            check(entries(dir,".part").size()==128,"Session shutdown retains complete unpublished files");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void retryAndSoftStop() throws Exception {
        Path dir=Files.createTempDirectory("hfx-retry-retain-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            Files.write(dir.resolve("file.bin"),OLD);
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Path complete=java.nio.file.Paths.get(fs.stagingPath(p.token));
            readHash(fs,p.token); fs.releaseVerification(p.token); fs.resetForRetry(p.token);
            Path retry=java.nio.file.Paths.get(fs.stagingPath(p.token));
            check(!complete.equals(retry),"Retry writes a new generation without truncating the complete copy");
            fill(fs,p.token,new byte[]{31,37});
            rejects(()->fs.publishUnverified(p.token),"Partial retry cannot be published as a complete unverified file");
            fs.retainUnverified(p.token);
            check(Arrays.equals(DATA,Files.readAllBytes(complete)),"Stopping retry retains the previous complete mismatch");
            check(Arrays.equals(OLD,Files.readAllBytes(dir.resolve("file.bin"))),"Stopping retry preserves the original target");
            check(Files.size(retry)==2,"Incomplete retry is retained for diagnosis");
            check(entries(dir,".claim").isEmpty(),"Retained diagnostics no longer block other sessions");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void unverifiedPublication() throws Exception {
        Path dir=Files.createTempDirectory("hfx-unverified-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            Files.write(dir.resolve("file.bin"),OLD);
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            String saved=fs.publishUnverified(p.token);
            check(!saved.equals(p.target),"Unverified overwrite uses a separate output name");
            check(Arrays.equals(OLD,Files.readAllBytes(dir.resolve("file.bin"))),"Unchecked data does not replace the original");
            check(Arrays.equals(DATA,Files.readAllBytes(java.nio.file.Paths.get(saved))),"Unchecked complete data remains usable");
            p=prepare(fs,dir,"new.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            check(fs.publishUnverified(p.token).equals(p.target),"An unused target receives its complete unchecked file");
            p=prepare(fs,dir,"kept.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Path part=java.nio.file.Paths.get(fs.stagingPath(p.token));
            fs.abandon(p.token,false);
            check(Files.exists(part),"Disabling resume never deletes a sealed complete generation");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void staleLeaseAndMutation() throws Exception {
        Path dir=Files.createTempDirectory("hfx-stale-reservation-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess(), next=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",true);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Path claim=entries(dir,".claim").get(0);
            byte[] oldClaim=Files.readAllBytes(claim);
            fs.endSession();
            Files.write(claim,oldClaim);
            TransferFileAccess.Prepared recovered=prepare(next,dir,"file.bin",true);
            check(recovered.offset==DATA.length,"A dead session lease permits safe checkpoint recovery");
            next.seal(recovered.token);
            Path part=java.nio.file.Paths.get(next.stagingPath(recovered.token));
            // The Windows JDK returns null fileKey; its creation/change stamps
            // detect this real replacement. Forged external timestamps are outside
            // the guarantee made by the cooperating-session reservation protocol.
            Files.delete(part); Files.write(part,new byte[]{1,1,1,1,1});
            rejects(()->next.openSealedRead(recovered.token),"A replaced sealed inode is rejected before hashing");
            check(!Files.exists(dir.resolve("file.bin")),"Changed sealed data is never published");
        } finally { fs.endSession(); next.endSession(); cleanup(dir); }
    }
    private static void metadataFailureKeepsCompleteCopy() throws Exception {
        Path dir=Files.createTempDirectory("hfx-retry-checkpoint-");
        final boolean[] fail={false};
        JdkTransferFileAccess fs=new JdkTransferFileAccess() {
            @Override protected void atomicMove(File source,File target,boolean replace) throws IOException {
                if (fail[0] && target.getName().endsWith(".meta")) throw new IOException("Synthetic checkpoint failure");
                super.atomicMove(source,target,replace);
            }
        };
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Path original=java.nio.file.Paths.get(fs.stagingPath(p.token));
            byte[] meta=Files.readAllBytes(sibling(original,".meta"));
            fail[0]=true;
            rejects(()->fs.resetForRetry(p.token),"A failed generation checkpoint aborts the retry");
            check(Arrays.equals(DATA,Files.readAllBytes(original)),"Checkpoint failure preserves complete bytes");
            check(Arrays.equals(meta,Files.readAllBytes(sibling(original,".meta"))),"Checkpoint failure preserves the durable old generation");
            fail[0]=false;
            readHash(fs,p.token); fs.completeVerification(p.token,hash(DATA),hash(DATA));
            check(Files.exists(dir.resolve("file.bin")),"The preserved generation remains verifiable after a failed retry");
        } finally { fail[0]=false; fs.endSession(); cleanup(dir); }
    }
    private static void closeActiveHandles() throws Exception {
        Path dir=Files.createTempDirectory("hfx-session-handles-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            TransferFileAccess.Handle handle=fs.openSealedRead(p.token);
            fs.endSession();
            check(!handle.channel.isOpen(),"End session closes a remaining hash reader");
            handle.close();
            check(entries(dir,".claim").isEmpty() && entries(dir,".lease").isEmpty(),"End session releases hash reservations");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void crossProcessReservation() throws Exception {
        Path dir=Files.createTempDirectory("hfx-process-lease-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            Process process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),
                    "-cp",System.getProperty("java.class.path"),"StagingVerificationTest","compete",dir.toString())
                    .redirectErrorStream(true).start();
            boolean exited=process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            check(exited && process.exitValue()==0,"An independent JVM cannot bypass a live sealed-file lease");
            check(Arrays.equals(DATA,Files.readAllBytes(java.nio.file.Paths.get(fs.stagingPath(p.token)))),
                    "Cross-process rejection preserves sealed bytes");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void fixedKeepBothTarget() throws Exception {
        Path dir=Files.createTempDirectory("hfx-retry-target-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess();
        try {
            Files.write(dir.resolve("file.bin"),OLD);
            TransferOptions options=options(false);
            options.conflictPolicy=TransferOptions.ConflictPolicy.KEEP_BOTH;
            TransferFileAccess.Prepared p=fs.prepare(dir.toString(),"file.bin",TransferHashes.text("file.bin"),
                    DATA.length,1000,"",options);
            fill(fs,p.token,DATA); fs.seal(p.token);
            fs.resetForRetry(p.token);
            fill(fs,p.token,DATA); fs.seal(p.token);
            readHash(fs,p.token); fs.completeVerification(p.token,hash(DATA),hash(DATA));
            check(p.target.endsWith("file (1).bin") && Files.exists(java.nio.file.Paths.get(p.target)),
                    "Retry commits to the originally selected KEEP_BOTH target");
            check(!Files.exists(dir.resolve("file (2).bin")),"Retry does not select another conflict suffix");
            check(Arrays.equals(OLD,Files.readAllBytes(dir.resolve("file.bin"))),"KEEP_BOTH retry preserves the original");
            check(entries(dir,".part").isEmpty(),"Successful retry removes its obsolete complete diagnostic generation");
        } finally { fs.endSession(); cleanup(dir); }
    }
    private static void unverifiedNameReservation() throws Exception {
        Path dir=Files.createTempDirectory("hfx-unverified-name-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess(), other=new JdkTransferFileAccess();
        try {
            Files.write(dir.resolve("file.bin"),OLD);
            TransferFileAccess.Prepared reserved=prepare(other,dir,"file (unverified 1).bin",false);
            fill(other,reserved.token,DATA); other.seal(reserved.token);
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA); fs.seal(p.token);
            check(fs.publishUnverified(p.token).endsWith("file (unverified 2).bin"),
                    "Unchecked publication skips a name sealed by another live session");
            check(!Files.exists(java.nio.file.Paths.get(reserved.target)),
                    "Unchecked publication cannot consume another session's reserved output name");
        } finally { fs.endSession(); other.endSession(); cleanup(dir); }
    }
    private static void externalDescriptorLifetime() throws Exception {
        Path dir=Files.createTempDirectory("hfx-external-descriptor-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess(), other=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            fill(fs,p.token,DATA);
            String writer=UUID.randomUUID().toString();
            fs.reserveExternalHandle(p.token,writer,false);
            rejects(()->fs.seal(p.token),"A remotely owned writer blocks sealing");
            fs.closeExternalHandle(p.token,writer); fs.closeExternalHandle(p.token,writer);
            fs.seal(p.token);
            String reader=UUID.randomUUID().toString();
            fs.reserveExternalHandle(p.token,reader,true);
            rejects(()->fs.completeVerification(p.token,hash(DATA),hash(DATA)),"A remotely owned reader blocks publication");
            rejects(()->fs.releaseVerification(p.token),"A remotely owned reader blocks lock release");
            fs.endSession(); fs.endSession();
            rejects(()->prepare(other,dir,"file.bin",false),"Repeated session shutdown keeps a late Binder descriptor reserved");
            rejects(()->fs.openStage(p.token),"A retiring token cannot grant another descriptor");
            fs.closeExternalHandle(p.token,reader);
            check(entries(dir,".claim").isEmpty() && entries(dir,".lease").isEmpty(),
                    "The late close acknowledgment releases the last reservation");
            check(entries(dir,".part").size()==1 && !Files.exists(dir.resolve("file.bin")),
                    "Closing a late descriptor retains the complete unpublished file");
            TransferFileAccess.Prepared resumed=prepare(other,dir,"file.bin",true);
            check(resumed.offset==DATA.length,"Another session can resume after the late close acknowledgment");
        } finally { fs.endSession(); other.endSession(); cleanup(dir); }
    }
    private static void crcOnlyAvoidsDeferredReservation() throws Exception {
        Path dir=Files.createTempDirectory("hfx-crc-fastpath-");
        JdkTransferFileAccess fs=new JdkTransferFileAccess(), other=new JdkTransferFileAccess();
        try {
            TransferFileAccess.Prepared p=prepare(fs,dir,"file.bin",false);
            check(entries(dir,".claim").isEmpty() && entries(dir,".lease").isEmpty(),
                    "A CRC-only write does not persist a deferred verification reservation");
            rejects(()->prepare(other,dir,"file.bin",false),"The original target lock still excludes another writer");
            fill(fs,p.token,DATA); fs.commit(p.token,false);
            check(Arrays.equals(DATA,Files.readAllBytes(dir.resolve("file.bin"))),"CRC-only publication remains correct");
            check(entries(dir,".claim").isEmpty() && entries(dir,".lease").isEmpty(),
                    "CRC-only publication never creates session claim or lease files");
        } finally { fs.endSession(); other.endSession(); cleanup(dir); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length==2 && "compete".equals(args[0])) {
            JdkTransferFileAccess fs=new JdkTransferFileAccess();
            try {
                fs.prepare(args[1],"file.bin",TransferHashes.text("different process"),DATA.length,1000,"",options(false));
                throw new AssertionError("Independent process acquired a live reservation");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("reserved by another")) throw expected;
            } finally { fs.endSession(); }
            return;
        }
        deferredAndReadOnly(); boundedDescriptors(); retryAndSoftStop(); unverifiedPublication();
        staleLeaseAndMutation(); metadataFailureKeepsCompleteCopy(); closeActiveHandles();
        crossProcessReservation(); fixedKeepBothTarget(); unverifiedNameReservation(); externalDescriptorLifetime();
        crcOnlyAvoidsDeferredReservation();
        System.out.println("StagingVerificationTest: "+checks+" checks passed");
    }
}
