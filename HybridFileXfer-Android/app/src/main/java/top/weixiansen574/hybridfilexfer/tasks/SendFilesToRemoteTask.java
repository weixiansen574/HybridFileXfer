package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.HFXServer;

public class SendFilesToRemoteTask extends TransferTask<SendFilesToRemoteTask.EventHandler> {
    List<String> files;
    String localDir;
    String remoteDir;

    public SendFilesToRemoteTask(EventHandler uiHandler, HFXServer server, List<String> files, String localDir, String remoteDir) {
        super(uiHandler,server);
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
    }

    @Override
    protected void onStart(EventHandler handler) throws Throwable {
        if (!server.requestRemoteReceive()) {
            handler.onRequestSendFailed();
            return;
        }
        startEventReceiveThread(handler);
        startSpeedMonitorThread(handler);
        String exceptionMessage = server.sendFilesToRemote(files, localDir, remoteDir);
        if (exceptionMessage == null) {
            handler.onTransferOver();
        } else {
            handler.onTransferFailed(exceptionMessage);
        }
        cancelSpeedMonitorThread();
    }

    public interface EventHandler extends TransferTask.EventHandler {
        void onRequestSendFailed();
    }

}
