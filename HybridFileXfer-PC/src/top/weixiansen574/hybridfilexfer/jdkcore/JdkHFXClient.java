package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkHFXClient extends HFXClient {

    public JdkHFXClient(String serverControllerAddress, int serverPort, String homeDir) {
        super(serverControllerAddress, serverPort, homeDir);
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    @Override
    public long getAvailableMemoryMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    @Override
    protected boolean deleteLocalFile(String path) throws Exception {
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
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }

    @Override
    protected boolean mkdir(String parent, String child) throws Exception {
        return new File(parent,child).mkdirs();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return Utils.listRemoteFiles(path);
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new JdkWriteFileCall(buffers,dequeCount);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new JdkReadFileCall(buffers,files,localDir,remoteDir,operateThreadCount);
    }
}
