package top.weixiansen574.hybridfilexfer;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.aidl.ParcelableRemoteFile;
/*
帮我生成对应的AIDL代码，要生成事务代码一起，例如destroy() = 16777114这种数字。
除了destroy方法，其余的数字从1开始递增
interface IIOService {
    void destroy() = 16777114;
    ParcelFileDescriptor createAndOpenWriteableFile(String path, long length) = 1;
    ParcelFileDescriptor openReadableFile(String path) = 2;
    ……
}
*/
public class IOServiceImpl extends IIOService.Stub {
    private final top.weixiansen574.hybridfilexfer.core.LocalTransferFileAccess transferFiles =
            new top.weixiansen574.hybridfilexfer.core.LocalTransferFileAccess() {
                @Override protected String fileIdentity(File file) throws IOException {
                    try {
                        android.system.StructStat stat=android.system.Os.stat(file.getPath());
                        return stat.st_dev+":"+stat.st_ino;
                    } catch (android.system.ErrnoException e) { throw new IOException(e); }
                }
                @Override protected boolean sameFile(File first, File second) throws IOException {
                    try {
                        android.system.StructStat a=android.system.Os.stat(first.getPath());
                        android.system.StructStat b=android.system.Os.stat(second.getPath());
                        return a.st_dev==b.st_dev && a.st_ino==b.st_ino;
                    } catch (android.system.ErrnoException e) { throw new IOException(e); }
                }
                @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
                    try {
                        if (replace) android.system.Os.rename(source.getPath(), target.getPath());
                        else NativeMemory.moveNoReplace(source.getPath(), target.getPath());
                    } catch (android.system.ErrnoException e) {
                        throw new IOException(e);
                    } catch (LinkageError e) {
                        // Privileged user-service ClassLoaders can lack the APK native path.
                        // Fail the commit and retain its verified staging file. Never replace
                        // this with a check-then-rename that could overwrite another file.
                        throw new IOException("Safe file publication is unavailable in this I/O process", e);
                    }
                }
            };

    public String[] describeTransferFile(String path) {
        try {
            top.weixiansen574.hybridfilexfer.core.bean.RemoteFile file = transferFiles.stat(path);
            return new String[]{file.getName(), file.getPath(), Long.toString(file.lastModified()),
                    Long.toString(file.getSize()), Boolean.toString(file.isDirectory())};
        } catch (IOException e) { return new String[]{e.toString()}; }
    }
    public void beginTransferFileSession() { transferFiles.beginSession(); }
    public void cancelPendingTransferOperations() { transferFiles.cancelPendingOperations(); }
    public String[] takeTransferFileWarnings() {
        return transferFiles.takeWarnings().toArray(new String[0]);
    }
    public String[] canonicalTransferPath(String path) {
        try { return new String[]{transferFiles.canonicalPath(path), ""}; }
        catch (IOException e) { return new String[]{"",e.toString()}; }
    }
    public ParcelFileDescriptor openTransferSource(String path) {
        try {
            if (transferFiles.stat(path).isDirectory()) return null;
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { return null; }
    }
    public String[] prepareTransferFile(String root, String relative, String identity, long size, long modified,
                                        String hash, int policy, boolean resume, boolean incremental) {
        try {
            top.weixiansen574.hybridfilexfer.core.TransferOptions options = new top.weixiansen574.hybridfilexfer.core.TransferOptions();
            options.conflictPolicy = top.weixiansen574.hybridfilexfer.core.TransferOptions.ConflictPolicy.values()[policy];
            options.resume = resume; options.incremental = incremental;
            return transferFiles.prepare(root, relative, identity, size, modified, hash, options).encode();
        } catch (IOException | RuntimeException e) {
            top.weixiansen574.hybridfilexfer.core.TransferFileAccess.Prepared p =
                    new top.weixiansen574.hybridfilexfer.core.TransferFileAccess.Prepared();
            p.status = "failed"; p.message = e.toString();
            return p.encode();
        }
    }
    public ParcelFileDescriptor openTransferStage(String token, String handleId) {
        try { return ParcelFileDescriptor.open(new File(transferFiles.reserveExternalHandle(token,handleId,false)), ParcelFileDescriptor.MODE_READ_WRITE); }
        catch (IOException e) {
            try { transferFiles.closeExternalHandle(token,handleId); } catch (IOException ignored) {}
            return null;
        }
    }
    public String sealTransferFile(String token) {
        try { transferFiles.seal(token); return null; } catch (IOException e) { return e.toString(); }
    }
    public ParcelFileDescriptor openSealedTransferFile(String token, String handleId) {
        try { return ParcelFileDescriptor.open(new File(transferFiles.reserveExternalHandle(token,handleId,true)), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException e) {
            try { transferFiles.closeExternalHandle(token,handleId); } catch (IOException ignored) {}
            try { transferFiles.releaseVerification(token); } catch (IOException ignored) {}
            return null;
        }
    }
    public String closeTransferHandle(String token, String handleId) {
        try { transferFiles.closeExternalHandle(token,handleId); return null; }
        catch (IOException e) { return e.toString(); }
    }
    public String completeTransferVerification(String token, String expected, String actual) {
        try { transferFiles.completeVerification(token,expected,actual); return null; }
        catch (IOException e) { return e.toString(); }
    }
    public String releaseTransferVerification(String token) {
        try { transferFiles.releaseVerification(token); return null; } catch (IOException e) { return e.toString(); }
    }
    public String resetTransferFileForRetry(String token) {
        try { transferFiles.resetForRetry(token); return null; } catch (IOException e) { return e.toString(); }
    }
    public String[] publishUnverifiedTransferFile(String token) {
        try { return new String[]{transferFiles.publishUnverified(token),""}; }
        catch (IOException e) { return new String[]{"",e.toString()}; }
    }
    public String retainUnverifiedTransferFile(String token) {
        try { transferFiles.retainUnverified(token); return null; } catch (IOException e) { return e.toString(); }
    }
    public String endTransferFileSession() {
        try { transferFiles.endSession(); return null; } catch (IOException e) { return e.toString(); }
    }
    public String checkpointTransferFile(String token, long offset) {
        try { transferFiles.checkpoint(token, offset); return null; }
        catch (IOException e) { return e.toString(); }
    }
    public String commitTransferFile(String token, boolean verifySha256) {
        try { transferFiles.commit(token, verifySha256); return null; }
        catch (IOException e) { return e.toString(); }
    }
    public String abandonTransferFile(String token, boolean keep) {
        try { transferFiles.abandon(token, keep); return null; }
        catch (IOException e) { return e.toString(); }
    }
    public String createTransferDirectory(String root, String relative, long modified) {
        try { transferFiles.createDirectory(root, relative, modified); return null; }
        catch (IOException e) { return e.toString(); }
    }
    //由于Parcel的限制，传输太大的文件列表信息会直接崩，所以要分块
    public static final int CHUNK_SIZE = 1000;
    private final AtomicInteger localFileListSliceId = new AtomicInteger(1);
    private final Map<Integer, List<ParcelableRemoteFile>> localFileListSliceMap = new HashMap<>();

    public void destroy(){
        System.exit(0);
    }

    public ParcelFileDescriptor createAndOpenWriteableFile(String path,long length){
        try {
            File file = new File(path);
            RandomAccessFile randomAccessFile = new RandomAccessFile(file,"rw");
            randomAccessFile.setLength(length);
            randomAccessFile.close();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public ParcelFileDescriptor openReadableFile(String path){
        try {
            return ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public int[] listFiles(String path) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        int totalSize = files.length;
        ArrayList<Integer> ids = new ArrayList<>(totalSize / CHUNK_SIZE + 1);
        for (int i = 0; i < totalSize; i += CHUNK_SIZE) {
            List<ParcelableRemoteFile> remoteFiles = new ArrayList<>();
            // 直接使用索引遍历，而不是创建子数组
            for (int j = i; j < Math.min(i + CHUNK_SIZE, totalSize); j++) {
                remoteFiles.add(new ParcelableRemoteFile(files[j]));
            }
            int id = localFileListSliceId.getAndAdd(1);
            ids.add(id);
            localFileListSliceMap.put(id, remoteFiles);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    public List<ParcelableRemoteFile> getAndRemoveFileListSlice(int sliceId) {
        return localFileListSliceMap.remove(sliceId);
    }

    public boolean setFileLastModified(String path,long time){
        return new File(path).setLastModified(time);
    }

    public boolean deleteFile(String path){
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }

    public boolean appendAndMkdirs(String parent, String child){
        return new File(parent,child).mkdirs();
    }

    public boolean mkdirs(String path){
        return new File(path).mkdirs();
    }

    public boolean fileExists(String path){
        return new File(path).exists();
    }

    public boolean isFile(String path){
        return new File(path).isFile();
    }

    public String getFileParent(String path){
        return new File(path).getParent();
    }

    /**
     * 创建文件的父路径如果不存在
     * @param path 文件的路径，然后创建的文件是它的parent
     * @return 异常信息
     */
    public String createParentDirIfNotExists(String path){
        File file = new File(path);
        File parentFile = file.getParentFile();
        //如果就是根目录的情况
        if (parentFile == null){
            return null;
        }
        return mkdirOrThrow(parentFile);
    }

    public String tryMkdirs(String path) {
        return mkdirOrThrow(new File(path));
    }

    private String mkdirOrThrow(File file){
        if (file.exists()) {
            //如果存在且是一个文件则删除再创建成文件夹
            if (file.isFile()) {
                if (file.delete()) {
                    if (!file.mkdirs()) {
                        return "cannot mkdirs " + file;
                    }
                } else {
                    return "cannot delete file " + file;
                }
            }
        } else {
            if (!file.mkdirs()) {
                return "cannot mkdirs " + file;
            }
        }
        return null;
    }

}
