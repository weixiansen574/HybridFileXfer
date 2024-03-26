package top.weixiansen574.hybridfilexfer.core.threads;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.JobPublisher;
import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.ServerInfo;
import top.weixiansen574.hybridfilexfer.core.bean.TransferJob;

public class ClientControllerThread extends Thread implements TransferThread.OnExceptionListener {

    Socket controllerSocket;
    DataInputStream dis;
    DataOutputStream dos;
    ReceiveThread usbReceiveThread;
    ReceiveThread wifiReceiveThread;
    JobPublisher jobPublisher;
    SendThread usbSendThread;
    SendThread wifiSendThread;
    BlockingDeque<FileTransferEvent> fileTransferEvents;

    public ClientControllerThread() {
        jobPublisher = new JobPublisher();
        fileTransferEvents = new LinkedBlockingDeque<>();
    }

    @Override
    public void run() {
        try {

            controllerSocket = new Socket(InetAddress.getLoopbackAddress(), ServerInfo.PORT_CONTROLLER);
            dis = new DataInputStream(controllerSocket.getInputStream());
            dos = new DataOutputStream(controllerSocket.getOutputStream());

            InetAddress serverWifiAddress;
            try {
                serverWifiAddress = getServerWifiAddress();
            } catch (IOException e){
                System.out.println("连接手机失败，因为服务端未运行！请在手机上启动服务器并等待连接！");
                return;
            }
            if (Arrays.equals(serverWifiAddress.getAddress(),new byte[4])){
                System.out.println("连接手机失败，因为手机没有连接WIFI！");
                return;
            }

            Socket wifiSocket = new Socket(serverWifiAddress, ServerInfo.PORT_WIFI);
            Socket usbSocket = new Socket(InetAddress.getLoopbackAddress(), ServerInfo.PORT_USB);//USB-ADB forward的端口是本地127.0.0.1

            //接收线程只管接收
            usbReceiveThread = new ReceiveThread(fileTransferEvents,ReceiveThread.DEVICE_USB,usbSocket.getInputStream());
            usbReceiveThread.setName("usbReceive");
            usbReceiveThread.setOnExceptionListener(this);
            usbReceiveThread.start();
            wifiReceiveThread = new ReceiveThread(fileTransferEvents,ReceiveThread.DEVICE_WIFI,wifiSocket.getInputStream());
            wifiReceiveThread.setName("wifiReceive");
            wifiReceiveThread.setOnExceptionListener(this);
            wifiReceiveThread.start();

            usbSendThread = new SendThread(fileTransferEvents,SendThread.DEVICE_USB,jobPublisher,usbSocket.getOutputStream());
            usbSendThread.setName("usbSend");
            usbSendThread.setOnExceptionListener(this);
            usbSendThread.start();
            wifiSendThread = new SendThread(fileTransferEvents,SendThread.DEVICE_WIFI,jobPublisher,wifiSocket.getOutputStream());
            wifiSendThread.setName("wifiSend");
            wifiSendThread.setOnExceptionListener(this);
            wifiSendThread.start();
            System.out.println("已连接至手机！WLAN IP:"+serverWifiAddress.getHostAddress());
            waitingForRequest();

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            onException(e);
        }
    }

    private InetAddress getServerWifiAddress() throws IOException {
        //发送获取WLAN地址请求
        dos.writeShort(ControllerIdentifiers.GET_WLAN_ADDRESS);
        //获取返回的WIFI地址
        byte[] wlanV4AddressBytes = new byte[4];
        dis.readFully(wlanV4AddressBytes);
        return Inet4Address.getByAddress(wlanV4AddressBytes);
    }

    private void waitingForRequest() throws IOException, ClassNotFoundException {
        w:while (true) {
            short identifiers = dis.readShort();
            switch (identifiers) {
                case ControllerIdentifiers.LIST_FILES:
                    String path = dis.readUTF();
                    System.out.println("获取文件列表 "+path);
                    handleListFiles(path);
                    break;
                case ControllerIdentifiers.TRANSPORT_FILES:
                    String serverDir = dis.readUTF();//服务器（手机）路径
                    String dir = dis.readUTF();//客户端（电脑）的路径
                    int contentLength = dis.readInt();//内容长度（文件列表）
                    byte[] bytes = new byte[contentLength];
                    dis.readFully(bytes);
                    ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                    ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
                    List<String> remoteFiles = (List<String>) objectInputStream.readObject();
                    inputStream.close();
                    handleTransferFileToServer(serverDir,dir,remoteFiles);
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    System.out.println("收到关闭指令，准备关闭连接……");
                    usbSendThread.shutdown();
                    wifiSendThread.shutdown();
                    try {
                        usbSendThread.join();
                        wifiSendThread.join();
                        usbReceiveThread.join();
                        wifiReceiveThread.join();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    controllerSocket.close();
                    break w;
            }
        }
    }

    private void handleListFiles(String path) throws IOException {

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            ArrayList<RemoteFile> files = new ArrayList<>();
            if (!path.equals("/")) {
                File file = new File(path);
                File[] list = file.listFiles();
                if (list != null) {
                    for (File file1 : list) {
                        files.add(new RemoteFile(file1));
                    }
                }
            } else {
                File[] roots = File.listRoots();
                for (File root : roots) {
                    files.add(new RemoteFile(root.getPath(),root.getPath(),root.lastModified(),root.length(),root.isDirectory()));
                }
            }
            objectOutputStream.writeObject(files);
            objectOutputStream.flush();
            byte[] bytes = byteArrayOutputStream.toByteArray();
            dos.writeInt(bytes.length);
            dos.write(bytes);
    }

    private void handleTransferFileToServer(String serverDir,String dir,List<String> remoteFiles){
        File localDir = new File(dir);
        List<File> transferFiles = new ArrayList<>();
        for (String remoteFile : remoteFiles) {
            transferFiles.add(new File(remoteFile));
        }
        jobPublisher.addJob(new TransferJob(localDir,serverDir,transferFiles));
    }


    @Override
    public void onException(Exception e) {
        System.exit(-1);
    }
}
