package top.weixiansen574.hybirdfilexfer.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class HFXClient {
    public static final byte[] clientHeader = new byte[]{'H', 'F', 'X', 'C'};
    public static final int VERSION_CODE = 200;

    String serverControllerAddress;
    int serverPort;
    Socket socket;
    DataInputStream dis;
    DataOutputStream dos;
    List<TransferChannel> transferChannels = new ArrayList<>();
    boolean isRun = true;

    public HFXClient(String serverControllerAddress, int serverPort) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
    }

    public boolean connect() throws IOException {
        try {
            socket = new Socket(serverControllerAddress, serverPort);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());
            dos.write(clientHeader);
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
            TransferChannel channel = new TransferChannel(name, socketChannel);
            transferChannels.add(channel);
            dos.writeBoolean(true);
            dos.writeUTF(name);
            dis.readBoolean();
        }
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
        if (transferChannels != null) {
            for (TransferChannel transferChannel : transferChannels) {
                try {
                    transferChannel.close();
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
                    dos.writeUTF(file.getPath());
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
        List<FutureTask<Void>> tasks = new ArrayList<>();
        for (TransferChannel transferChannel : transferChannels) {
            FutureTask<Void> task = new FutureTask<>(new ReceiveFilesCall(transferChannel));
            tasks.add(task);
            Thread thread = new Thread(task);
            thread.setName(transferChannel.name + "_receive");
            thread.start();
        }
        dos.writeBoolean(true);
        for (FutureTask<Void> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                isRun = false;
                System.err.println("接收文件时发生异常，客户端已退出");
                return;
            }
        }
        System.out.println("传输完毕");
    }

    private void handleSendFiles() throws IOException {
        int listSize = dis.readInt();
        List<File> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new File(dis.readUTF()));
        }
        String remoteDir = dis.readUTF();//对方的localDir
        String localDir = dis.readUTF();//对方为remoteDir
        System.out.println("发送文件，本地路径：" + localDir + "，对方路径：" + remoteDir + "，文件列表:" + fileList);
        JobPool jobPool = new JobPool(new File(localDir), remoteDir, fileList);
        List<FutureTask<Void>> tasks = new ArrayList<>();
        for (TransferChannel transferChannel : transferChannels) {
            FutureTask<Void> task = new FutureTask<>(new SendFilesCall(transferChannel, jobPool));
            tasks.add(task);
            Thread thread = new Thread(task);
            thread.setName(transferChannel.name + "_send");
            thread.start();
        }
        dos.writeBoolean(true);
        for (FutureTask<Void> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                isRun = false;
                System.err.println("发送文件时发生异常，客户端已退出");
                return;
            }
        }
        System.out.println("发送完毕");
    }


}
