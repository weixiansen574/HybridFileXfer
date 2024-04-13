package top.weixiansen574.hybridfilexfer.async;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.IIServiceFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.Utils;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;


public class CDParentTask extends BackstageTask<CDParentTask.EventHandler> {
    IIServiceFileSelectAdapter adapter;
    String path;

    public CDParentTask(EventHandler handle, IIServiceFileSelectAdapter adapter, String path) {
        super(handle);
        this.adapter = adapter;
        this.path = path;
    }

    @Override
    protected void onStart(EventHandler eventHandler) throws Throwable {
        String parentPath = Utils.getParentByPath(path);
        System.out.println("parentPath:"+parentPath);
        if (parentPath != null) {
            List<ParcelableRemoteFile> files = null;
            files = adapter.listTargetFiles(parentPath);
            if (files == null) {
                eventHandler.onPermissionDenied();
            } else if (files.size() > 0) {
                Utils.sortFiles(files);
                eventHandler.onSuccess(files,parentPath);
            } else {
                eventHandler.onParentDirNotFiles();
            }
        } else {
            eventHandler.onThisIsTheLastPage();
        }
    }

    public interface EventHandler extends BaseEventHandler{
        void onSuccess(List<ParcelableRemoteFile> files,String parentPath);
        void onPermissionDenied();
        void onParentDirNotFiles();
        void onThisIsTheLastPage();
    }
}
