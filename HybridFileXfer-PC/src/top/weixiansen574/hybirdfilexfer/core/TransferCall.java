package top.weixiansen574.hybirdfilexfer.core;

import top.weixiansen574.hybirdfilexfer.core.TransferChannel;

import java.io.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;

public abstract class TransferCall implements Callable<Void> {
    protected final TransferChannel tChannel;
    protected SocketChannel socketChannel;
    protected DataInputStream dis;
    protected DataOutputStream dos;

    public TransferCall(TransferChannel tChannel) {
        this.tChannel = tChannel;
        socketChannel = tChannel.socketChannel;
        dis = tChannel.dis;
        dos = tChannel.dos;
    }

    public static synchronized RandomAccessFile newRAF(File path, long totalSize) throws IOException {
        if (!path.exists()) {
            RandomAccessFile randomAccessFile = new RandomAccessFile(path,"rw");
            randomAccessFile.setLength(totalSize);
            return randomAccessFile;
        } else {
            return new RandomAccessFile(path,"rw");
        }
    }

}
