package top.weixiansen574.hybridfilexfer.droidcore;

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
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
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
        server.startServer(port,interfaceList,localBufferCount,remoteBufferCount,callback);
    }

    @Override
    protected void onError(Throwable th) {
        server.close();
    }
}
