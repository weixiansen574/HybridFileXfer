package top.weixiansen574.hybridfilexfer.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public class TransferChannel {
    public final String iName;
    //TrafficInfo totalTraffic;
    TrafficInfo currentTraffic;
    public SocketChannel socketChannel;
    public DataInputStream dis;
    public DataOutputStream dos;

    public TransferChannel(String iName, SocketChannel socketChannel) throws IOException {
        this.iName = iName;
        this.socketChannel = socketChannel;
        currentTraffic = new TrafficInfo();
        dis = new DataInputStream(socketChannel.socket().getInputStream());
        dos = new DataOutputStream(socketChannel.socket().getOutputStream());
    }

    public synchronized void addUploadedBytes(long byteCount){
       currentTraffic.uploadTraffic +=byteCount;
    }

    public synchronized void addDownloadedBytes(long byteCount){
        currentTraffic.downloadTraffic +=byteCount;
    }

    public synchronized TrafficInfo takeCurrentTrafficInfo(){
        TrafficInfo info = currentTraffic;
        currentTraffic = new TrafficInfo(iName);
        return info;
    }

    public void close() throws IOException {
        socketChannel.close();
    }
}

