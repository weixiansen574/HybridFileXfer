package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;

public class StartServerTask extends BackstageTask<StartServerTask.EventHandler> {
    private final HFXServer server;
    private final List<ServerNetInterface> interfaceList;

    public StartServerTask(EventHandler uiHandler, HFXServer server, List<ServerNetInterface> interfaceList) {
        super(uiHandler);
        this.server = server;
        this.interfaceList = interfaceList;
    }

    @Override
    protected void onStart(EventHandler handler) throws Throwable {
        if (!server.startServer(5740)) {
            handler.onBindFailed(5740);
            return;
        }
        handler.onStatedServer();
        if (server.waitConnect(interfaceList,handler)) {
            handler.onConnectSuccess();
        }
    }

    public interface EventHandler extends BaseEventHandler,HFXServer.WaitConnectCallBack{
        void onStatedServer();
        void onBindFailed(int port);
        void onConnectSuccess();
    }
}
