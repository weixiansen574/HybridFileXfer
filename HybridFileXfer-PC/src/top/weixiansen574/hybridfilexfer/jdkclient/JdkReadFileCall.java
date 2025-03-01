package top.weixiansen574.hybridfilexfer.jdkclient;

import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkReadFileCall extends ReadFileCall {
    private final Map<FileChannel,RandomAccessFile> map = new HashMap<>();

    public JdkReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        super(buffers, files, localDir, remoteDir, operateThreadCount);
    }

    @Override
    protected boolean fileExists(String path) throws Exception {
        return new File(path).exists();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(files.length);
        for (File file : files) {
            remoteFiles.add(new RemoteFile(file));
        }
        return remoteFiles;
    }

    @Override
    protected FileChannel openFile(String path) throws Exception {
        RandomAccessFile randomAccessFile = new RandomAccessFile(path,"r");
        FileChannel channel = randomAccessFile.getChannel();
        map.put(channel,randomAccessFile);
        return channel;
    }

    @Override
    protected void closeFile(FileChannel channel) throws Exception {
        RandomAccessFile randomAccessFile = map.get(channel);
        channel.close();
        if (randomAccessFile != null){
            randomAccessFile.close();
        }
    }
}
