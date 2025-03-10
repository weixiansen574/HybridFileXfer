package top.weixiansen574.hybridfilexfer.tasks;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.droidcore.DroidHFXClient;

public class ConnectServerTask extends BackstageTask<ConnectServerTask.Callback> {
    private final DroidHFXClient client;
    public ConnectServerTask(Callback uiHandler, DroidHFXClient client) {
        super(uiHandler);
        this.client = client;
    }

    @Override
    protected void onStart(Callback callback) throws Throwable {
        if (!client.connect(callback)) {
            client.freeBuffers();
        }
    }

    @Override
    protected void onError(Throwable th) {
        client.freeBuffers();
    }

    public interface Callback extends ConnectServerCallback,BackstageTask.BaseEventHandler{
    }
}
