package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkReadFileCall extends ReadFileCall {
    private RandomAccessFile randomAccessFile;
    private FileChannel channel;

    public JdkReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        super(buffers, files, localDir, remoteDir, operateThreadCount);
    }

    @Override
    protected boolean fileExists(String path) throws Exception {
        return new File(path).exists();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return Utils.listRemoteFiles(path);
    }

    @Override
    protected FileChannel openFile(String path) throws Exception {
        randomAccessFile = new RandomAccessFile(path, "r");
        channel = randomAccessFile.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        randomAccessFile.close();
    }
}
