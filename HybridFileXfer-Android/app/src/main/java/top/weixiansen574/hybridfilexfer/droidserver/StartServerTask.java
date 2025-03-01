package top.weixiansen574.hybridfilexfer.droidserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidserver.callback.StartServerCallback;
import top.weixiansen574.nio.DataByteChannel;

public class StartServerTask extends BackstageTask<StartServerCallback> {
    private final HFXServer server;
    private final int port;
    private final List<ServerNetInterface> interfaceList;
    private final int localBufferCount;
    private final int remoteBufferCount;

    public StartServerTask(StartServerCallback uiHandler, HFXServer server, int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount) {
        super(uiHandler);
        this.server = server;
        this.port = port;
        this.interfaceList = interfaceList;
        this.localBufferCount = localBufferCount;
        this.remoteBufferCount = remoteBufferCount;
    }

    @Override
    protected void onStart(StartServerCallback callback) throws Throwable {
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        server.serverSocketChannel = serverSocketChannel;
        callback.onStatedServer();
        //控制通道
        DataByteChannel ctChannel;
        //传输通道
        List<TransferConnection> connections;
        //第一此accept的为控制通道
        conn:
        while (true) {
            //协议判断
            ctChannel = new DataByteChannel(serverSocketChannel.accept());
            byte[] headerBytes = HFXServer.CLIENT_HEADER.getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[headerBytes.length];
            ctChannel.readFully(header);
            if (!Arrays.equals(headerBytes, header)) {
                ctChannel.writeBytes("protocol error\n");
                ctChannel.close();
                continue;
            }
            //版本判断
            int versionCode = ctChannel.readInt();
            if (versionCode != HFXServer.VERSION_CODE) {
                //返回当前服务端版本信息
                ctChannel.writeBoolean(false);//版本未正确匹配
                ctChannel.writeInt(HFXServer.VERSION_CODE);
                ctChannel.close();
                continue;
            }
            ctChannel.writeBoolean(true);//版本正确匹配
            ctChannel.writeInt(interfaceList.size());//网卡IP数量
            for (ServerNetInterface netInterface : interfaceList) {
                byte[] address = netInterface.address.getAddress();
                ctChannel.writeUTF(netInterface.name);//网卡名称
                ctChannel.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                ctChannel.write(address);//地址
                if (netInterface.clientBindAddress == null) {
                    ctChannel.writeByte(0);//地址长度（null:0）
                } else {
                    byte[] bAddress = netInterface.clientBindAddress.getAddress();
                    ctChannel.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                    ctChannel.write(bAddress);//地址
                }
            }
            //连接传输通道
            connections = new ArrayList<>(interfaceList.size());
            for (int i = 0; i < interfaceList.size(); i++) {
                //对方连接通道是否成功
                boolean succeed = ctChannel.readBoolean();
                //对方所连接通道的名称（由控制器通道发送名称）
                String name = ctChannel.readUTF();
                if (succeed) {
                    //accept通道
                    connections.add(new TransferConnection(name, new DataByteChannel(serverSocketChannel.accept())));
                    ctChannel.writeBoolean(true);
                    callback.onAccepted(name);
                } else {
                    ctChannel.writeBoolean(false);
                    ctChannel.close();
                    callback.onAcceptFailed(name);
                    continue conn;
                }
            }
            break;
        }

        LinkedBlockingDeque<ByteBuffer> buffers = server.buffers;
        //告知对方创建的缓冲区块数量
        ctChannel.writeInt(remoteBufferCount);
        if (!ctChannel.readBoolean()) {
            callback.onPcOOM();
            serverSocketChannel.close();
            return;
        }
        //创建缓冲区块
        for (int i = 0; i < localBufferCount; i++) {
            ByteBuffer buffer = NativeMemory.allocateLargeBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null) {
                buffers.add(buffer);
            } else {
                System.out.println("缓冲区块创建失败，已创建：" + i + "，需要" + localBufferCount);
                //释放缓冲区块内存
                for (ByteBuffer buff : buffers) {
                    NativeMemory.freeBuffer(buff);
                }
                buffers.clear();
                ctChannel.writeBoolean(false);
                serverSocketChannel.close();
                callback.onMeOOM(i, localBufferCount);
                return;
            }
        }
        ctChannel.writeBoolean(true);
        //读取对方文件系统信息
        server.remoteFileSystem = ctChannel.readInt();
        server.ctChannel = ctChannel;
        server.connections = connections;
        callback.onConnectSuccess();
    }

    @Override
    protected void onError(Throwable th) {
        //释放并清理缓冲区块
        for (ByteBuffer buffer : server.buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        server.buffers.clear();
        //关闭serverSocket，释放端口绑定，不用管其他accept的Socket
        if (server.serverSocketChannel != null) {
            try {
                server.serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
