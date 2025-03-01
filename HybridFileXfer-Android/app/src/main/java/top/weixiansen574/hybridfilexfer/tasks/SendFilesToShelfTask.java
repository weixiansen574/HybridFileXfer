package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.droidserver.HFXServer;

public class SendFilesToShelfTask extends BackstageTask<BTransferFileCallback> {
    private final HFXServer server;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    public SendFilesToShelfTask(BTransferFileCallback uiHandler, HFXServer server, List<RemoteFile> files, Directory localDir, Directory remoteDir) {
        super(uiHandler);
        this.server = server;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
    }

    @Override
    protected void onStart(BTransferFileCallback callback) throws Throwable {
        server.sendFilesToShelf(files,localDir,remoteDir,callback);
    }
}
