package top.weixiansen574.hybridfilexfer.async;

import static top.weixiansen574.hybridfilexfer.Utils.sortFiles;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.IIServiceFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public class CDTask extends BackstageTask<CDTask.EventHandle> {
    IIServiceFileSelectAdapter adapter;
    String path;

    public CDTask(EventHandle handle, IIServiceFileSelectAdapter adapter, String path) {
        super(handle);
        this.adapter = adapter;
        this.path = path;
    }

    @Override
    protected void onStart(EventHandle eventHandler) throws Throwable {
        List<ParcelableRemoteFile> files = adapter.listTargetFiles(path);
        if (files == null) {
            eventHandler.onPermissionDenied();
        } else {
            sortFiles(files);
            eventHandler.onSuccess(files,path);
        }
    }

    public interface EventHandle extends BaseEventHandler{
        void onSuccess(List<ParcelableRemoteFile> files,String path);
        void onPermissionDenied();
    }
}
