package top.weixiansen574.hybridfilexfer.droidserver;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class DroidReadFileCall extends ReadFileCall {
    private final Map<FileChannel,OpenedFileEntry> map = new HashMap<>();
    private final IIOService ioService;

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
        ParcelFileDescriptor pfd = ioService.openReadableFile(path);
        FileInputStream fileInputStream = new FileInputStream(pfd.getFileDescriptor());
        FileChannel fileChannel = fileInputStream.getChannel();
        map.put(fileChannel,new OpenedFileEntry(fileInputStream,pfd));
        return fileChannel;
    }

    @Override
    protected void closeFile(FileChannel channel) throws Exception {
        OpenedFileEntry openedFileEntry = map.get(channel);
        channel.close();
        assert openedFileEntry != null;
        openedFileEntry.fileInputStream.close();
        openedFileEntry.pfd.close();
    }

    private static class OpenedFileEntry {
        FileInputStream fileInputStream;
        ParcelFileDescriptor pfd;

        public OpenedFileEntry(FileInputStream fileInputStream, ParcelFileDescriptor pfd) {
            this.fileInputStream = fileInputStream;
            this.pfd = pfd;
        }
    }
}
