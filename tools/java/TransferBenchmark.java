import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import top.weixiansen574.hybridfilexfer.core.*;
import top.weixiansen574.hybridfilexfer.core.bean.*;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkTransferFileAccess;
import top.weixiansen574.hybridfilexfer.security.SecureRecordChannel;
import top.weixiansen574.nio.DataByteChannel;

/** Explicit benchmark entry point, deliberately outside the automatic test discovery tree. */
public final class TransferBenchmark {
    private static final class Observer implements TransferFileCallback {
        public void onFileUploading(String i,String p,long n,long t) {}
        public void onFileDownloading(String i,String p,long n,long t) {}
        public void onSpeedInfo(List<TrafficInfo> t) {}
        public void onChannelComplete(String i,long n,long t) {}
        public void onChannelError(String i,int t,String m) { System.err.println(i+": "+m); }
        public void onReadFileError(String m) { System.err.println(m); }
        public void onWriteFileError(String m) { System.err.println(m); }
        public void onComplete(boolean u,long n,long t) {}
        public void onIncomplete() {}
    }
    private static final class Pair implements AutoCloseable {
        final ByteChannel a,b;
        Pair() throws IOException {
            try(ServerSocketChannel listener=ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1",0));
                SocketChannel one=SocketChannel.open(listener.getLocalAddress()),two=listener.accept();
                one.socket().setTcpNoDelay(true); two.socket().setTcpNoDelay(true);
                SecureRandom random=new SecureRandom();
                byte[] ka=new byte[32],kb=new byte[32],sa=new byte[4],sb=new byte[4];
                random.nextBytes(ka); random.nextBytes(kb); random.nextBytes(sa); random.nextBytes(sb);
                a=new SecureRecordChannel(one,ka,kb,sa,sb);
                b=new SecureRecordChannel(two,kb,ka,sb,sa);
            }
        }
        public void close() throws IOException { a.close(); b.close(); }
    }
    private static void generate(Path path,long size,long seed) throws IOException {
        Random random=new Random(seed);
        byte[] block=new byte[64*1024];
        try(OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)) {
            while(size>0) {
                random.nextBytes(block);
                int count=(int)Math.min(size,block.length);
                out.write(block,0,count); size-=count;
            }
        }
    }
    private static void removeGeneratedTree(Path root) throws IOException {
        // Only the uniquely created benchmark directory is eligible for recursive cleanup.
        Path temp=Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path absolute=root.toAbsolutePath().normalize();
        if(!absolute.startsWith(temp) || !absolute.getFileName().toString().startsWith("hfx-benchmark-"))
            throw new IOException("Refusing unexpected benchmark cleanup path");
        try(java.util.stream.Stream<Path> paths=Files.walk(absolute)) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    private static String measure(Path source,Path destination,List<RemoteFile> files,int channels,String scenario,int iteration,boolean sha256)
            throws Exception {
        List<Pair> pairs=new ArrayList<>();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        ReliableTransfer sender=null,receiver=null;
        try {
            Pair control=new Pair(); pairs.add(control);
            List<TransferConnection> outgoing=new ArrayList<>(),incoming=new ArrayList<>();
            for(int i=0;i<channels;i++) {
                Pair pair=new Pair(); pairs.add(pair);
                outgoing.add(new TransferConnection("loopback-"+i,new DataByteChannel(pair.a)));
                incoming.add(new TransferConnection("loopback-"+i,new DataByteChannel(pair.b)));
            }
            sender=new ReliableTransfer(new JdkTransferFileAccess(),new DataByteChannel(control.a),outgoing,
                    new LinkedBlockingDeque<ByteBuffer>(),new Observer());
            receiver=new ReliableTransfer(new JdkTransferFileAccess(),new DataByteChannel(control.b),incoming,
                    new LinkedBlockingDeque<ByteBuffer>(),new Observer());
            TransferOptions options=new TransferOptions();
            options.verifySha256=sha256; options.resume=true; options.prescan=true; options.timeoutSeconds=120;
            ReliableTransfer a=sender,b=receiver;
            long started=System.nanoTime();
            Future<Boolean> receive=executor.submit(()->b.receive(options,true));
            Future<Boolean> send=executor.submit(()->a.send(files,
                    new Directory(source.toString(),Directory.getCurrentFileSystem()),
                    new Directory(destination.toString(),Directory.getCurrentFileSystem()),options));
            send.get(30,TimeUnit.MINUTES); receive.get(30,TimeUnit.MINUTES);
            long elapsed=System.nanoTime()-started;
            TransferSummary summary=receiver.getSummary();
            if(!summary.isSuccessful() || !sender.getSummary().isSuccessful())
                throw new IOException("Benchmark transfer failed: "+summary.failures);
            double milliseconds=elapsed/1_000_000.0;
            double throughput=summary.transferredBytes/(1024.0*1024.0)/(elapsed/1_000_000_000.0);
            return String.format(Locale.ROOT,"%s,%d,%d,%d,%.3f,%.3f,%d,%d,%b,true",
                    scenario,iteration,channels,summary.transferredBytes,milliseconds,throughput,
                    summary.completedFiles,summary.failedFiles,sha256);
        } finally {
            if(sender!=null) sender.cancel(); if(receiver!=null) receiver.cancel();
            for(Pair pair:pairs) pair.close();
            executor.shutdownNow(); executor.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=7) throw new IllegalArgumentException("largeMiB smallFiles smallKiB channelsCSV repetitions outputCSV sha256");
        int largeMiB=Integer.parseInt(args[0]),smallFiles=Integer.parseInt(args[1]),smallKiB=Integer.parseInt(args[2]);
        int repetitions=Integer.parseInt(args[4]);
        if(!args[6].equals("true") && !args[6].equals("false")) throw new IllegalArgumentException("sha256 must be true or false");
        boolean sha256=Boolean.parseBoolean(args[6]);
        if(largeMiB<1 || largeMiB>16384 || smallFiles<1 || smallFiles>100000 || smallKiB<1 || smallKiB>1024
                || repetitions<1 || repetitions>20) throw new IllegalArgumentException("Benchmark dimensions out of bounds");
        List<Integer> counts=new ArrayList<>();
        for(String value:args[3].split(",")) {
            int count=Integer.parseInt(value);
            if(count<1 || count>16) throw new IllegalArgumentException("Use 1..16 channels");
            counts.add(count);
        }
        Path output=Paths.get(args[5]).toAbsolutePath();
        if(output.getParent()!=null) Files.createDirectories(output.getParent());
        Path root=Files.createTempDirectory("hfx-benchmark-");
        try(BufferedWriter csv=Files.newBufferedWriter(output)) {
            Path source=Files.createDirectory(root.resolve("source"));
            Path large=source.resolve("large.bin");
            generate(large,largeMiB*1024L*1024L,574);
            List<RemoteFile> largeSet=Collections.singletonList(new RemoteFile(large.toFile()));
            List<RemoteFile> smallSet=new ArrayList<>();
            for(int i=0;i<smallFiles;i++) {
                Path file=source.resolve(String.format(Locale.ROOT,"small-%06d.bin",i));
                generate(file,smallKiB*1024L,i);
                smallSet.add(new RemoteFile(file.toFile()));
            }
            String header="scenario,iteration,channels,bytes,elapsed_ms,MiB_per_second,completed_files,failed_files,sha256,encrypted";
            csv.write(header); csv.newLine(); System.out.println(header);
            for(int repetition=1;repetition<=repetitions;repetition++) {
                // Alternate channel order to reduce simple warm-cache/order bias.
                List<Integer> order=new ArrayList<>(counts);
                if(repetition%2==0) Collections.reverse(order);
                for(int channels:order) for(String scenario:Arrays.asList("large","small")) {
                    Path destination=Files.createDirectory(root.resolve(scenario+"-"+channels+"-"+repetition));
                    String line=measure(source,destination,scenario.equals("large")?largeSet:smallSet,channels,scenario,repetition,sha256);
                    csv.write(line); csv.newLine(); csv.flush(); System.out.println(line);
                }
            }
        } finally { removeGeneratedTree(root); }
        System.out.println("Saved: "+output);
    }
}
