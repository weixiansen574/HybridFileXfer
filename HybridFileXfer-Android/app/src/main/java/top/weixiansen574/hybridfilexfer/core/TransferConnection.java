package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.nio.DataByteChannel;

public class TransferConnection {
    public final String iName;
    /** 头字段读写用的包装通道 */
    public final DataByteChannel channel;
    /**
     * 原始 SocketChannel。sendfile 零拷贝必须直接把数据写进它，
     * 不能经过 {@link DataByteChannel} 包装，否则 JDK 会退化成普通拷贝。
     */
    public final SocketChannel socket;

    private TrafficInfo currentTraffic;
    private TrafficInfo totalTraffic;

    public TransferConnection(String iName, SocketChannel socket) throws IOException {
        this.iName = iName;
        this.socket = socket;
        tuneSocket(socket);
        this.channel = new DataByteChannel(socket);
        //必须带上名字：第一次采样取到的就是这个对象，名字缺失会打印 null
        currentTraffic = new TrafficInfo(iName);
        totalTraffic = new TrafficInfo(iName);
    }

    /**
     * 传输通道的 socket 调优。所有通道（客户端连出的 + 服务端 accept 的）都会经过这个构造器，
     * 因此这里是唯一需要设置的地方。
     * <p>
     * 这里<b>只</b>开 TCP_NODELAY，<b>不</b>显式设置 SO_SNDBUF/SO_RCVBUF：
     * 一旦应用层显式设置这两个值，内核就会关闭发送/接收窗口的自动调整
     * （Linux 的 tcp_moderate_rcvbuf、Windows 的 receive-window auto-tuning）。
     * 在速率和 RTT 都在波动的无线链路上，固定缓冲只会比自动调整更差。
     */
    private static void tuneSocket(SocketChannel socket) {
        try {
            //块头是小包（约 100 字节），不关 Nagle 会被扣住等 ACK，
            //等于每个块的边界上白等一个 RTT。
            socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception ignored) {
        }
    }

    public synchronized void addUploadedBytes(long byteCount) {
        currentTraffic.uploadTraffic += byteCount;
        totalTraffic.uploadTraffic += byteCount;
    }

    public synchronized void addDownloadedBytes(long byteCount) {
        currentTraffic.downloadTraffic += byteCount;
        totalTraffic.downloadTraffic += byteCount;
    }

    public synchronized TrafficInfo resetCurrentTrafficInfo() {
        TrafficInfo info = currentTraffic;
        currentTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo resetTotalTrafficInfo() {
        TrafficInfo info = totalTraffic;
        totalTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo getTotalTraffic(){
        return totalTraffic;
    }

    public void close() throws IOException {
        channel.close();
    }
}
