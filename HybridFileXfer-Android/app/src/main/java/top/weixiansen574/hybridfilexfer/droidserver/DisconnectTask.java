package top.weixiansen574.hybridfilexfer.droidserver;

import java.io.IOException;
import java.nio.ByteBuffer;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;

public class DisconnectTask extends BackstageTask<BackstageTask.BaseEventHandler> {
    private final HFXServer server;
    public DisconnectTask(BaseEventHandler uiHandler, HFXServer server) {
        super(uiHandler);
        this.server = server;
    }

    @Override
    protected void onStart(BaseEventHandler eventHandlerProxy) throws Throwable {
        //释放缓冲区块内存
        for (ByteBuffer buffer : server.buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        server.buffers.clear();
        if (server.serverSocketChannel != null) {
            if (server.ctChannel != null) {
                try {
                    //通知断开连接
                    server.ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                } catch (IOException ignored) {
                }
            }
            try {
                server.serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        //关闭所有用于传输的连接
        if (server.connections != null) {
            for (TransferConnection connection : server.connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
