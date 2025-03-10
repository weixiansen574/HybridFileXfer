package top.weixiansen574.hybridfilexfer.droidcore;

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
        server.disconnect();
    }
}
