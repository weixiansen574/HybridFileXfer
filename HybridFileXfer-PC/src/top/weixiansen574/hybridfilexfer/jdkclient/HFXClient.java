package top.weixiansen574.hybridfilexfer.jdkclient;

import top.weixiansen574.hybridfilexfer.core.*;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

public class HFXClient {
    public static final byte[] CLIENT_HEADER = new byte[]{'H', 'F', 'X', 'C'};
    public static final int VERSION_CODE = 300;

    String serverControllerAddress;
    int serverPort;
    Socket socket;
    DataInputStream dis;
    DataOutputStream dos;
    List<TransferConnection> connections = new ArrayList<>();
    LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    boolean isRun = true;
    TransferFileCallback callback = new TransferFileCallback() {
        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {

        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {

        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {

        }

        @Override
        public void onReadFileError(String message) {

        }

        @Override
        public void onWriteFileError(String message) {

        }

        @Override
        public void onComplete(long traffic, long time) {

        }

        @Override
        public void onIncomplete() {

        }
    };

    public HFXClient(String serverControllerAddress, int serverPort) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
    }

    public boolean connect() throws IOException {
        try {
            System.out.println("正在连接控制通道：" + serverControllerAddress);
            socket = new Socket(serverControllerAddress, serverPort);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());
            dos.write(CLIENT_HEADER);
            dos.writeInt(VERSION_CODE);
            if (!dis.readBoolean()) {
                System.out.println("版本不一致，你的版本：" + VERSION_CODE + "，对方版本：" + dis.readInt());
                socket.close();
                return false;
            }
        } catch (IOException e) {
            System.out.println("控制通道连接到手机失败，请检查手机的服务端是否启动？");
            return false;
        }
        int ipCount = dis.readInt();
        String[] names = new String[ipCount];
        InetAddress[] addresses = new InetAddress[ipCount];
        InetAddress[] bindAddresses = new InetAddress[ipCount];

        for (int i = 0; i < ipCount; i++) {
            String name = dis.readUTF();
            byte[] address = new byte[dis.readByte()];
            dis.readFully(address);
            InetAddress inetAddress = InetAddress.getByAddress(address);
            byte l46 = dis.readByte();
            InetAddress bindAddress = null;
            if (l46 != 0) {
                byte[] bAddress = new byte[l46];
                dis.readFully(bAddress);
                bindAddress = InetAddress.getByAddress(bAddress);
            }
            names[i] = name;
            addresses[i] = inetAddress;
            bindAddresses[i] = bindAddress;
        }

        for (int i = 0; i < ipCount; i++) {
            SocketChannel socketChannel;
            String name = names[i];
            InetAddress inetAddress = addresses[i];
            InetAddress bindAddress = bindAddresses[i];
            System.out.printf("正在连接 网卡名：%s 远程地址：%s 绑定地址：%s\n", name, inetAddress.getHostAddress(), bindAddress == null ?
                    "null" : bindAddress.getHostAddress());
            if (name.equals("USB_ADB") && !serverControllerAddress.equals("127.0.0.1")) {
                System.err.println("错误：你在手机上选用了USB_ADB网卡，但没有使用ADB进行连接");
                dos.writeBoolean(false);
                socket.close();
                return false;
            }
            if (bindAddress == null) {
                socketChannel = SocketChannel.open(new InetSocketAddress(inetAddress, serverPort));
            } else {
                socketChannel = SocketChannel.open();
                socketChannel.bind(new InetSocketAddress(bindAddress, 0));
                socketChannel.connect(new InetSocketAddress(inetAddress, serverPort));
            }
            connections.add(new TransferConnection(name, new DataByteChannel(socketChannel)));

            /*TransferChannel channel = new TransferChannel(name, new Socket(inetAddress,serverPort));
            transferChannels.add(channel);*/
            dos.writeBoolean(true);
            dos.writeUTF(name);
            dis.readBoolean();
        }
        //初始化缓冲区块
        int bufferCount = dis.readInt();
        for (int i = 0; i < bufferCount; i++) {
            try {
                buffers.add(ByteBuffer.allocate(FileBlock.BLOCK_SIZE));
            } catch (OutOfMemoryError error) {
                buffers.clear();
                String arch = System.getProperty("os.arch");
                long maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                System.out.println("内存不足，创建缓冲区块失败！请尝试调小缓冲区块数（1MB每块）。成功创建" + i + "块，需要"
                        + bufferCount + "块。当前JVM最大内存：" + maxMemoryMB + "MB");
                if (!arch.contains("64")) {
                    System.out.println("检测你正在使用32位Java，内存受限，建议使用64位Java");
                }
                dos.writeBoolean(false);
                return false;
            }
        }
        dos.writeBoolean(true);
        if (!dis.readBoolean()) {
            System.out.println("连接失败，手机端内存不足，请调小缓存区块数");
            return false;
        }
        //返回文件系统信息给对方
        dos.writeInt(Directory.getCurrentFileSystem());
        System.out.println("传输通道已全部连接完成");
        return true;
    }

    public void start() throws IOException {
        //LOOP
        while (isRun) {
            short id = dis.readShort();
            switch (id) {
                case ControllerIdentifiers.LIST_FILES:
                    handleListFiles();
                    break;
                case ControllerIdentifiers.DELETE_FILE:
                    handleDeleteFile();
                    break;
                case ControllerIdentifiers.MKDIR:
                    handleMkdir();
                    break;
                case ControllerIdentifiers.REQUEST_RECEIVE:
                    handleReceiveFiles();
                    break;
                case ControllerIdentifiers.REQUEST_SEND:
                    handleSendFiles();
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    handleShutdown();
                    break;
            }
        }
    }


    private void handleDeleteFile() throws IOException {
        dos.writeBoolean(deleteLocalFile(dis.readUTF()));
    }

    public boolean deleteLocalFile(String path) {
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

    private void handleMkdir() throws IOException {
        String parent = dis.readUTF();
        String child = dis.readUTF();
        dos.writeBoolean(new File(parent, child).mkdirs());
    }

    private void handleShutdown() {
        isRun = false;
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (connections != null) {
            for (TransferConnection transferConnection : connections) {
                try {
                    transferConnection.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        System.out.println("收到停止指令，客户端已正常关闭！");
    }

    private void handleListFiles() throws IOException {
        String path = dis.readUTF();
        if (!path.equals("/")) {
            File file = new File(path);
            File[] list = file.listFiles();
            if (list != null) {
                dos.writeInt(list.length);
                for (File file1 : list) {
                    writeFile(file1);
                }
            } else {
                dos.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            //判断是否是Linux的目录结构，Windows的根目录是C:\\，而不是所有盘符，Linux的根目录是“/”没有盘符概念
            if (roots.length == 1 && roots[0].getAbsolutePath().equals("/")) {
                roots = roots[0].listFiles();
                if (roots == null) {
                    dos.writeInt(-1);
                    //throw new RuntimeException("无法获取根目录下的文件列表，请检查运行时权限");
                    return;
                }
                dos.writeInt(roots.length);
                for (File root : roots) {
                    writeFile(root);
                }
            } else {
                dos.writeInt(roots.length);
                for (File file : roots) {
                    dos.writeUTF(file.getPath());//不要getName，否则空白
                    dos.writeUTF(file.getPath());
                    dos.writeLong(file.lastModified());
                    dos.writeLong(file.length());
                    dos.writeBoolean(file.isDirectory());
                }
            }
        }
        //已弃用ObjectOutputStream
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |

    }

    private void writeFile(File file) throws IOException {
        dos.writeUTF(file.getName());
        dos.writeUTF(file.getPath());
        dos.writeLong(file.lastModified());
        dos.writeLong(file.length());
        dos.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        System.out.println("准备接收");
        WriteFileCall writeFileCall = new JdkWriteFileCall(buffers, connections.size());
        List<FutureTask<Void>> tasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i,connection,writeFileCall,callback));
            tasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread thread = new Thread(writeFileTask);
        thread.setName("FileWrite");
        thread.start();
        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause();
            dos.writeBoolean(false);
            String ex = cause != null ? cause.toString() : e.toString();
            dos.writeUTF(ex);
            System.out.println("写入文件时发生异常，传输终止，异常信息：" + ex);
            return;
        }
        for (FutureTask<Void> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                isRun = false;
                return;
            }
        }
        dos.writeBoolean(true);
        if (dis.readBoolean()) {
            System.out.println("接收完成");
        } else {
            System.out.println("接收失败，因为对方读取文件时发生错误，错误信息："+dis.readUTF());
        }

    }

    private void handleSendFiles() throws IOException {
        int listSize = dis.readInt();
        List<RemoteFile> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new RemoteFile(new File(dis.readUTF())));
        }

        Directory remoteDir = new Directory(dis.readUTF(),dis.readInt());//对方的localDir
        Directory localDir = new Directory(dis.readUTF(),Directory.getCurrentFileSystem());//对方为remoteDir

        System.out.println("发送文件，本地路径：" + localDir + "，对方路径：" + remoteDir + "，文件列表:" + fileList);

        ReadFileCall readFileCall = new JdkReadFileCall(buffers,fileList,localDir,remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask);
        readThread.setName("FileRead");
        readThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall,connection,callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }

        //其中一条通道断掉，可能控制器通道也一起跟着断了
        boolean complete;
        try {
            //等待客户端接收成功或者写入到硬盘时发生IO错误
            complete = dis.readBoolean();
        } catch (IOException e){
            isRun = false;
            System.out.println("传输通道异常断开，文件传输失败！");
            return;
        }

        if (!complete) {
            String errMsg = dis.readUTF();
            System.out.println("写入文件时发生异常，传输终止，异常信息：" + errMsg);
            readFileCall.shutdownByWriteError();
            return;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                isRun = false;
                System.out.println("传输通道异常断开，文件传输失败！");
                return;
            }
        }

        try {
            readFileTask.get();
            dos.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            System.out.println("读取文件时发生异常，传输终止，异常信息：" + ex);
            dos.writeBoolean(false);
            dos.writeUTF(ex);
            return;
        }

        System.out.println("发送完毕");
    }

}
