package top.weixiansen574.hybridfilexfer.core;

import android.os.RemoteException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.hybridfilexfer.IHFXService;
import top.weixiansen574.hybridfilexfer.MyException;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.SocketConnectStatus;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;


public class HFXService extends IHFXService.Stub {
    public static final byte[] clientHeader = new byte[]{'H', 'F', 'X', 'C'};
    public static final int VERSION_CODE = 200;
    Socket controllerSocket;
    DataInputStream cDis;
    DataOutputStream cDos;
    List<TransferChannel> channels;
    ServerSocketChannel serverSocketChannel;
    public static final int CHUNK_SIZE = 1000;
    AtomicInteger localFileListSliceId = new AtomicInteger(1);
    private final Map<Integer,List<RemoteFile>> localFileListSliceMap = new HashMap<>();

    AtomicInteger remoteFileListSliceId = new AtomicInteger(1);
    private final Map<Integer,List<RemoteFile>> remoteFileListSliceMap = new HashMap<>();
    private TransferEventDeque eventDeque;

    //top.weixiansen574.HybridFileXFer2
    @Override
    public void destroy() {
        closeServer();
        System.exit(0);
    }

    /*@Override
    public void exit() {

        destroy();
    }*/

    public void closeServer(){
        if (serverSocketChannel != null){
            if (controllerSocket != null) {
                try {
                    notifyShutdown();
                    System.out.println("notifyShutdown");
                } catch (IOException ignored) {
                }
            }
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (channels!=null){
            for (TransferChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 绑定端口
     * @return 是否成功，否为端口被占用
     */
    @Override
    public boolean bind(int port){
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 等待控制器通道被连接
     * @param interfaceList 网卡列表
     * @return 是否连接完成，T:连接完成 F:服务端被强制关闭
     */
    @Override
    public boolean waitToConnect(List<ServerNetInterface> interfaceList){
        while (true) {
            try {
                Socket socket;
                try {
                    socket = serverSocketChannel.accept().socket();
                } catch (IOException e){
                    return false;
                }
                DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
                DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
                //协议判断
                byte[] header = new byte[4];
                dataInputStream.readFully(header);
                if (!Arrays.equals(clientHeader, header)) {
                    dataOutputStream.writeBytes("protocol error\n");
                    socket.close();
                    continue;
                }
                //版本判断
                int versionCode = dataInputStream.readInt();
                if (versionCode != VERSION_CODE) {
                    //返回当前服务端版本信息
                    dataOutputStream.writeBoolean(false);//版本未正确匹配
                    dataOutputStream.writeInt(VERSION_CODE);
                    socket.close();
                    continue;
                }
                controllerSocket = socket;
                cDis = dataInputStream;
                cDos = dataOutputStream;
                cDos.writeBoolean(true);//版本正确匹配
                cDos.writeInt(interfaceList.size());//网卡IP数量

                for (ServerNetInterface netInterface : interfaceList) {
                    byte[] address = netInterface.address.getAddress();
                    cDos.writeUTF(netInterface.name);//网卡名称
                    cDos.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                    cDos.write(address);//地址
                    if (netInterface.clientBindAddress == null) {
                        cDos.writeByte(0);//地址长度（null:0）
                    } else {
                        byte[] bAddress = netInterface.clientBindAddress.getAddress();
                        cDos.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                        cDos.write(bAddress);//地址
                    }
                }
                channels = new ArrayList<>(interfaceList.size());
                return true;
            } catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    @Override
    public SocketConnectStatus acceptTransferChannel(){
        try {
            boolean succeed = cDis.readBoolean();
            String name = cDis.readUTF();
            if (succeed){
                channels.add(new TransferChannel(name, serverSocketChannel.accept()));
                cDos.writeBoolean(true);
            } else {
                cDos.writeBoolean(false);
                controllerSocket.close();
                channels = null;
            }
            return new SocketConnectStatus(name,succeed);
        } catch (IOException e) {
            try {
                controllerSocket.close();
            } catch (IOException ignored) {
            }
            channels = null;
            return null;
        }
    }

    public void notifyShutdown() throws IOException {
        cDos.writeShort(ControllerIdentifiers.SHUTDOWN);
    }

    public List<String> getChannelListINames(){
        if (channels == null){
            return null;
        }
        List<String> iNames = new ArrayList<>(channels.size());
        for (TransferChannel channel : channels) {
            iNames.add(channel.iName);
        }
        return iNames;
    }

    public TransferEvent getCurrentTransferEvent(){
        return eventDeque.remove();
    }

    @Override
    public void test() throws RemoteException {
        throw new MyException("123");
        /*throw new NullPointerException("123");*/
    }


    public int[] listLocalFiles(String path) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null){
            return null;
        }
        int totalSize = files.length;
        ArrayList<Integer> ids = new ArrayList<>(totalSize/CHUNK_SIZE + 1);
        for (int i = 0; i < totalSize; i += CHUNK_SIZE) {
            List<RemoteFile> remoteFiles = new ArrayList<>();
            // 直接使用索引遍历，而不是创建子数组
            for (int j = i; j < Math.min(i + CHUNK_SIZE, totalSize); j++) {
                remoteFiles.add(new RemoteFile(files[j]));
            }
            int id = localFileListSliceId.getAndAdd(1);
            ids.add(id);
            localFileListSliceMap.put(id,remoteFiles);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    public List<RemoteFile> getAndRemoveLocalFileListSlice(int sliceId){
        return localFileListSliceMap.remove(sliceId);
    }

    public boolean writeListFiles(String path){
        try {
            cDos.writeShort(ControllerIdentifiers.LIST_FILES);
            cDos.writeUTF(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public int[] listClientFiles() {
        List<RemoteFile> clientFiles;
        try {
            clientFiles = listClientRemoteFiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (clientFiles == null){
            return null;
        }
        int totalSize = clientFiles.size();
        ArrayList<Integer> ids = new ArrayList<>(totalSize/CHUNK_SIZE + 1);
        for (int i = 0; i < totalSize; i += CHUNK_SIZE) {
            List<RemoteFile> remoteFiles = new ArrayList<>();
            for (int j = i; j < Math.min(i + CHUNK_SIZE, totalSize); j++) {
                remoteFiles.add(new RemoteFile(clientFiles.get(j)));
            }
            int id = remoteFileListSliceId.getAndAdd(1);
            ids.add(id);
            remoteFileListSliceMap.put(id,remoteFiles);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    public List<RemoteFile> getAndRemoveRemoteFileListSlice(int sliceId){
        return remoteFileListSliceMap.remove(sliceId);
    }

    public synchronized boolean requestRemoteReceive(){
        try {
            cDos.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
            cDis.readBoolean();//等待对方启动线程接收
            eventDeque = new TransferEventDeque();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public synchronized String sendFilesToRemote(List<String> files, String localDir, String remoteDir){
       /* try {
            cDos.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
            cDis.readBoolean();//等待对方启动线程接收
        } catch (IOException e) {
            return e.toString();
        }*/

        List<File> fileList = new ArrayList<>(files.size());
        for (String file : files) {
            fileList.add(new File(file));
        }

        JobPool jobPool = new JobPool(new File(localDir),remoteDir,fileList);
        ArrayList<FutureTask<Void>> tasks = new ArrayList<>(channels.size());
        for (TransferChannel channel : channels) {
            FutureTask<Void> task = new FutureTask<>(new SendFilesCall(channel,eventDeque,jobPool));
            Thread thread = new Thread(task);
            thread.setName(channel.iName +"_send");
            thread.start();
            tasks.add(task);
        }

        Exception exception = null;
        for (FutureTask<Void> task : tasks) {
            try {
                task.get();
            } catch (ExecutionException | InterruptedException e) {
                exception = e;
            }
        }
        eventDeque.release();
        if (exception != null){
            System.out.println("传输错误");
            exception.printStackTrace();
            return exception.toString();
        } else {
            System.out.println("传输完毕");
            return null;
        }
    }

    public synchronized boolean requestRemoteSend(List<String> files, String localDir, String remoteDir){
        try {
            cDos.writeShort(ControllerIdentifiers.REQUEST_SEND);
            cDos.writeInt(files.size());
            for (String file : files) {
                cDos.writeUTF(file);
            }
            cDos.writeUTF(localDir);
            cDos.writeUTF(remoteDir);
            cDis.readBoolean();//等待对方启动线程接收
            eventDeque = new TransferEventDeque();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public synchronized String receiveFiles(){
        List<FutureTask<Void>> tasks = new ArrayList<>();
        for (TransferChannel transferChannel : channels) {
            FutureTask<Void> task = new FutureTask<>(new ReceiveFilesCall(transferChannel,eventDeque));
            tasks.add(task);
            Thread thread = new Thread(task);
            thread.setName(transferChannel.iName + "_receive");
            thread.start();
        }
        Exception exception = null;
        for (FutureTask<Void> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                exception = e;
            }
        }
        eventDeque.release();
        if (exception != null){
            System.out.println("传输错误");
            exception.printStackTrace();
            return exception.toString();
        } else {
            System.out.println("传输完毕");
            return null;
        }
    }

    public List<TrafficInfo> getTrafficInfoList(){
        if (channels == null){
            return null;
        }
        List<TrafficInfo> trafficInfoList = new ArrayList<>();
        for (TransferChannel channel : channels) {
            trafficInfoList.add(channel.takeCurrentTrafficInfo());
        }
        return trafficInfoList;
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

    public boolean deleteRemoteFile(String file){
        try {
            cDos.writeShort(ControllerIdentifiers.DELETE_FILE);
            cDos.writeUTF(file);
            return cDis.readBoolean();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean createLocalDir(String parent,String child){
        File file = new File(parent,child);
        return file.mkdirs();
    }

    public boolean createRemoteDir(String parent,String child){
        try {
            cDos.writeShort(ControllerIdentifiers.MKDIR);
            cDos.writeUTF(parent);
            cDos.writeUTF(child);
            return cDis.readBoolean();
        } catch (IOException e) {
            return false;
        }
    }

    private List<RemoteFile> listClientRemoteFiles() throws IOException {
        //这里不用json了，导入json库会让服务端jar膨胀250kb，直接用字节流传输
        int listSize = cDis.readInt();
        if (listSize == -1){
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
        for (int i = 0; i < listSize; i++) {
            RemoteFile remoteFile = new RemoteFile(
                    cDis.readUTF(),//name
                    cDis.readUTF(),//path
                    cDis.readLong(),//lastModified
                    cDis.readLong(),//size
                    cDis.readBoolean()//isDirectory
            );
            remoteFiles.add(remoteFile);
        }
        return remoteFiles;
    }


}
