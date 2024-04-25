package top.weixiansen574.hybridfilexfer.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;
import top.weixiansen574.hybridfilexfer.core.bean.TransferredBytesInfo;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TransferJob;
import top.weixiansen574.hybridfilexfer.core.threads.ReceiveThread;
import top.weixiansen574.hybridfilexfer.core.threads.SendThread;
import top.weixiansen574.hybridfilexfer.core.threads.TransferThread;

public class FileTransferServer implements ServerInfo, TransferThread.OnExceptionListener {

    JobPublisher jobPublisher;
    Socket socket;
    DataInputStream dis;
    DataOutputStream dos;
    SendThread usbSendThread;
    ReceiveThread usbReceiveThread;
    SendThread wifiSendThread;
    ReceiveThread wifiReceiveThread;
    public final BlockingDeque<FileTransferEvent> fileTransferEvents;
    ServerSocket controllerSocket;
    ServerSocket usbServerSocket;
    ServerSocket wifiServerSocket;

    public FileTransferServer() {
        fileTransferEvents = new LinkedBlockingDeque<>();
    }

    public void startServer() throws IOException {
        this.jobPublisher = new JobPublisher();
        controllerSocket = new ServerSocket(ServerInfo.PORT_CONTROLLER);
        socket = controllerSocket.accept();
        this.dis = new DataInputStream(socket.getInputStream());
        this.dos = new DataOutputStream(socket.getOutputStream());

        short identifier = dis.readShort();
        switch (identifier) {
            case ControllerIdentifiers.GET_WLAN_ADDRESS:
                handleGetWifiAddress();
                waitPCConnect();
                break;
        }
    }

    private void handleGetWifiAddress() throws IOException {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String interfaceName = networkInterface.getName();
                        if (interfaceName.contains("wlan")) {
                            System.out.println("Interface: " + networkInterface.getName());
                            System.out.println("    IPv4 Address: " + inetAddress.getHostAddress());
                            dos.write(inetAddress.getAddress());
                            return;
                        }
                    }
                }
            }
            dos.write(new byte[4]);//如果没有WLAN地址，可能是没连WIFI，返回0.0.0.0
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
    }

    private void waitPCConnect() throws IOException {
        usbServerSocket = new ServerSocket(ServerInfo.PORT_USB);
        wifiServerSocket = new ServerSocket(ServerInfo.PORT_WIFI);

        Socket usbSocket = usbServerSocket.accept();
        System.out.println("USB通道已连接至电脑！");
        Socket wifiSocket = wifiServerSocket.accept();
        System.out.println("WIFI通道已连接至电脑！");

        usbSendThread = new SendThread(fileTransferEvents, SendThread.DEVICE_USB, jobPublisher, usbSocket.getOutputStream());
        usbSendThread.setOnExceptionListener(this);
        usbSendThread.setName("usbSend");
        usbSendThread.start();
        usbReceiveThread = new ReceiveThread(fileTransferEvents, ReceiveThread.DEVICE_USB, usbSocket.getInputStream());
        usbReceiveThread.setOnExceptionListener(this);
        usbReceiveThread.setName("usbReceive");
        usbReceiveThread.start();

        wifiSendThread = new SendThread(fileTransferEvents, SendThread.DEVICE_WIFI, jobPublisher, wifiSocket.getOutputStream());
        wifiSendThread.setOnExceptionListener(this);
        wifiSendThread.setName("wifiSend");
        wifiSendThread.start();
        wifiReceiveThread = new ReceiveThread(fileTransferEvents, ReceiveThread.DEVICE_WIFI, wifiSocket.getInputStream());
        wifiReceiveThread.setName("wifiReceive");
        wifiReceiveThread.setOnExceptionListener(this);
        wifiReceiveThread.start();

    }

    public ArrayList<RemoteFile> listClientFiles(String path) throws IOException {
        dos.writeShort(ControllerIdentifiers.LIST_FILES);
        dos.writeUTF(path);
        //这里不用json了，导入json库会让服务端jar膨胀250kb，直接用字节流传输
        int listSize = dis.readInt();
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
        for (int i = 0; i < listSize; i++) {
            RemoteFile remoteFile = new RemoteFile(
                    dis.readUTF(),//name
                    dis.readUTF(),//path
                    dis.readLong(),//lastModified
                    dis.readLong(),//size
                    dis.readBoolean()//isDirectory
            );
            remoteFiles.add(remoteFile);
        }
        return remoteFiles;

        /*
        因为安卓release版会删减无用代码，导致RemoteFile序列化ID不一致
        objectInputStream.readObject方法将出现异常，导致电脑端目录显示是空白
        再就是服务端不支持夸语言，仅限于java
        已弃用以下代码

        int contentLength = dis.readInt();
        byte[] content = new byte[contentLength];
        dis.readFully(content);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(content);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        try {
            ArrayList<RemoteFile> files = (ArrayList<RemoteFile>) objectInputStream.readObject();
            return files;
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        } finally {
            byteArrayInputStream.close();
        }*/
    }

    public void transferToMe(List<String> files, String remoteDir, String localDir) throws IOException {
        dos.writeShort(ControllerIdentifiers.TRANSPORT_FILES);//标识，传输文件到服务端
        dos.writeUTF(localDir);//服务端路径(手机)
        dos.writeUTF(remoteDir);//客户端路径(电脑)
        dos.writeInt(files.size());//文件数量
        for (String file : files) {
            dos.writeUTF(file);//文件路径
        }
        //ObjectInputStream可能会出现序列化不一致相关问题，已弃用
        /*ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        objectOutputStream.writeObject(files);
        objectOutputStream.flush();
        byte[] bytes = outputStream.toByteArray();
        outputStream.close();
        dos.writeInt(bytes.length);//content length
        dos.write(bytes);//要传输到服务端的文件列表*/
    }

    public void transferToClient(List<File> files, File localDir, String remoteDir) {
        jobPublisher.addJob(new TransferJob(localDir, remoteDir, files));
    }

    public FileTransferEvent getNextTransferEvent() throws InterruptedException {
        return fileTransferEvents.take();
    }

    public TransferredBytesInfo getTransferredBytesInfo() {
        return new TransferredBytesInfo(
                usbReceiveThread.getAndResetTransferredBytes(),
                usbSendThread.getAndResetTransferredBytes(),
                wifiReceiveThread.getAndResetTransferredBytes(),
                wifiSendThread.getAndResetTransferredBytes());
    }

    public void stopServer() {
        if (socket != null){
            try {
                sendShutdownToClientAndClose();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        /*先shutdown（interrupt)这两个线程，再join，等待它们结束
        如果这样写
        if (usbSendThread != null){
            usbSendThread.shutdown();
            joinNoException(usbSendThread);
        }
        如果第一个线程并未在获取任务时阻塞，而是sync状态，导致发送interrupt()不起作用，从而导致死锁
        * */

        if (usbSendThread != null){
            usbSendThread.shutdown();
        }if (wifiSendThread != null) {
            wifiSendThread.shutdown();
        }
        if (usbSendThread != null) {
            joinNoException(usbSendThread);
            System.out.println("usbSendThread stopped");
        }
        if (wifiSendThread != null) {
            joinNoException(wifiReceiveThread);
            System.out.println("wifiSendThread stopped");
        }
        if (usbReceiveThread != null){
            joinNoException(usbReceiveThread);
            System.out.println("usbReceiveThread stopped");
        }
        if (wifiReceiveThread != null){
            joinNoException(wifiReceiveThread);
            System.out.println("wifiReceiveThread stopped");
        }
        if (controllerSocket != null){
            closeNoException(controllerSocket);
        }
        if (usbServerSocket != null) {
            closeNoException(usbServerSocket);
        }
        if (wifiServerSocket != null){
            closeNoException(wifiServerSocket);
        }
        System.out.println("已执行stopServer");
    }

    private void sendShutdownToClientAndClose() throws IOException {
        dos.writeShort(ControllerIdentifiers.SHUTDOWN);
        dos.close();
    }
    private void closeNoException(Closeable closeable){
        try {
            closeable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void joinNoException(Thread thread){
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onException(Exception e) {
        System.exit(-1);
    }
}
