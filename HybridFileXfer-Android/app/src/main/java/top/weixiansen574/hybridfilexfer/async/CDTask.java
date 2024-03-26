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
            eventHandler.sendEmptyEventMessage(EventHandle.PERMISSION_DENIED);
            //Toast.makeText(context, "无权访问", Toast.LENGTH_SHORT).show();
        } else {
            sortFiles(files);
            eventHandler.sendEventMessage(EventHandle.SUCCESS,files,path);
        }
    }

    public abstract static class EventHandle extends IIServiceRequestHandler{
        public static final int SUCCESS = 0;
        public static final int PERMISSION_DENIED = 1;

        public EventHandle(ErrorHandler errorHandler) {
            super(errorHandler);
        }
    }
}
