package top.weixiansen574.hybridfilexfer.droidcore;

import android.app.ActivityManager;
import android.content.Context;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class DroidHFXClient extends HFXClient {
    private final IIOService iioService;
    private final Context context;
    public DroidHFXClient(String serverControllerAddress, int serverPort,String homeDir, IIOService iioService, Context context) {
        super(serverControllerAddress, serverPort,homeDir);
        this.iioService = iioService;
        this.context = context;
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        return NativeMemory.allocateLargeBuffer(size);
    }

    @Override
    public long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected boolean deleteLocalFile(String path) throws Exception {
        return iioService.deleteFile(path);
    }

    @Override
    protected boolean mkdir(String parent, String child) throws Exception {
        return iioService.appendAndMkdirs(parent,child);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(iioService,path);
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers,dequeCount,iioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(iioService,buffers,files,localDir,remoteDir,operateThreadCount);
    }

    public void freeBuffers(){
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }
}
