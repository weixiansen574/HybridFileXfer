package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkWriteFileCall extends WriteFileCall {
    private RandomAccessFile file;
    private FileChannel channel;

    public JdkWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        super(buffers, dequeCount);
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        File file = new File(path);
        File parentFile = file.getParentFile();
        //如果就是根目录的情况
        if (parentFile == null) {
            return;
        }
        mkdirOrThrow(parentFile);
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        mkdirOrThrow(new File(path));
    }

    @Override
    protected FileChannel createAndOpenFile(String path, long length) throws Exception {
        file = new RandomAccessFile(path, "rw");
        file.setLength(length);
        channel = file.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        file.close();
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        return new File(path).setLastModified(time);
    }

    private void mkdirOrThrow(File file) throws IOException {
        if (file.exists()) {
            //如果存在且是一个文件则删除再创建成文件夹
            if (file.isFile()) {
                if (file.delete()) {
                    if (!file.mkdirs()) {
                        throw new IOException("cannot mkdirs " + file);
                    }
                } else {
                    throw new IOException("cannot delete file " + file);
                }
            }
        } else {
            if (!file.mkdirs()) {
                throw new IOException("cannot mkdirs " + file);
            }
        }
    }
}
