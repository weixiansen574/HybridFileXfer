package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.listadapter.FileSelectAdapter;

public class ListFilesTask extends BackstageTask<ListFilesTask.CallBack> {

    public final String path;
    public final FileSelectAdapter adapter;

    public ListFilesTask(CallBack uiHandler, String path, FileSelectAdapter adapter) {
        super(uiHandler);
        this.path = path;
        this.adapter = adapter;
    }

    @Override
    protected void onStart(CallBack eventHandlerProxy) throws Throwable {
        List<RemoteFile> listFiles = adapter.listFiles(path);
        if (listFiles != null) {
            Utils.sortFiles(listFiles);
            listFiles.add(0,new RemoteFile("..", "", 0, 0, true));
            eventHandlerProxy.onResult(listFiles);
        } else {
            eventHandlerProxy.onResult(null);
        }
    }

    public interface CallBack extends BackstageTask.BaseEventHandler{
        void onResult(List<RemoteFile> files);
    }
}
