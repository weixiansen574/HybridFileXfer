package top.weixiansen574.hybridfilexfer.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;

public abstract class TransferCall implements Callable<Void> {
    public static final int CHUNK_SIZE = 128 * 1024; // 128KB
    protected final TransferChannel tChannel;
    protected SocketChannel socketChannel;
    protected DataInputStream dis;
    protected DataOutputStream dos;
    protected TransferEventDeque eventDeque;

    public TransferCall(TransferChannel tChannel,TransferEventDeque eventDeque) {
        this.tChannel = tChannel;
        this.eventDeque = eventDeque;
        socketChannel = tChannel.socketChannel;
        dis = tChannel.dis;
        dos = tChannel.dos;
    }

}
