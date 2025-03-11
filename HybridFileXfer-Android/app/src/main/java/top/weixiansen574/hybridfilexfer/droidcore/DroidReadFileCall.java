package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class DroidReadFileCall extends ReadFileCall {
    private final IIOService ioService;

    private ParcelFileDescriptor pfd;
    private FileInputStream fileInputStream;
    private FileChannel channel;

    public DroidReadFileCall(IIOService ioService,LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        super(buffers, files, localDir, remoteDir, operateThreadCount);
        this.ioService = ioService;
    }

    @Override
    protected boolean fileExists(String path) throws Exception {
        return ioService.fileExists(path);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(ioService,path);
    }

    @Override
    protected FileChannel openFile(String path) throws Exception {
        pfd = ioService.openReadableFile(path);
        fileInputStream = new FileInputStream(pfd.getFileDescriptor());
        channel = fileInputStream.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        fileInputStream.close();
        pfd.close();
    }

}
