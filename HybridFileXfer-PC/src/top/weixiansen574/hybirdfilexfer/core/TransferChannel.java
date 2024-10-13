package top.weixiansen574.hybirdfilexfer.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;


public class TransferChannel {
    public final String name;
    protected long transferredBytes;
    public SocketChannel socketChannel;
    public DataInputStream dis;
    public DataOutputStream dos;

    public TransferChannel(String name, SocketChannel socketChannel) throws IOException {
        this.name = name;
        this.socketChannel = socketChannel;
        dis = new DataInputStream(socketChannel.socket().getInputStream());
        dos = new DataOutputStream(socketChannel.socket().getOutputStream());
    }

    public void close() throws IOException {
        socketChannel.close();
    }


}

