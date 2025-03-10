package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

public class DroidWriteFileCall extends WriteFileCall {
    private final IIOService ioService;
    private final Map<FileChannel,OpenedFileEntry> map = new HashMap<>();
    public DroidWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, IIOService ioService) {
        super(buffers, dequeCount);
        this.ioService = ioService;
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        String exception = ioService.createParentDirIfNotExists(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        String exception = ioService.tryMkdirs(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected FileChannel createAndOpenFile(String path, long length) throws Exception {
        ParcelFileDescriptor pfd = ioService.createAndOpenWriteableFile(path, length);
        FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
        FileChannel channel = fileOutputStream.getChannel();
        OpenedFileEntry openedFileEntry = new OpenedFileEntry(fileOutputStream, pfd);
        map.put(channel,openedFileEntry);
        return channel;
    }

    @Override
    protected void closeFile(FileChannel channel) throws Exception {
        OpenedFileEntry openedFileEntry = map.get(channel);
        channel.close();
        if (openedFileEntry != null){
            openedFileEntry.fileOutputStream.close();
            openedFileEntry.pfd.close();
        }
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        return ioService.setFileLastModified(path,time);
    }


    private static class OpenedFileEntry {
        FileOutputStream fileOutputStream;
        ParcelFileDescriptor pfd;

        public OpenedFileEntry(FileOutputStream fileOutputStream, ParcelFileDescriptor pfd) {
            this.fileOutputStream = fileOutputStream;
            this.pfd = pfd;
        }
    }

}
