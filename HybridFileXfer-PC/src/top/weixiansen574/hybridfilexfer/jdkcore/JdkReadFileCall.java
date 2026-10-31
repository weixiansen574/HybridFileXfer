package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.SourceHandle;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkReadFileCall extends ReadFileCall {

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
    public SourceHandle openSource(String path) throws Exception {
        final RandomAccessFile raf = new RandomAccessFile(path, "r");
        final FileChannel channel = raf.getChannel();
        return new SourceHandle() {
            @Override
            public FileChannel channel() {
                return channel;
            }

            @Override
            public void close() throws IOException {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                raf.close();
            }
        };
    }
}
