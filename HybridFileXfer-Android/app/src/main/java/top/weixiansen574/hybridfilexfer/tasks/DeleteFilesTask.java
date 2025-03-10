package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.listadapter.FileSelectAdapter;

public class DeleteFilesTask extends BackstageTask<DeleteFilesTask.EventHandler> {

    final FileSelectAdapter adapter;
    final List<String> files;

    public DeleteFilesTask(EventHandler uiHandler, FileSelectAdapter adapter, List<String> files) {
        super(uiHandler);
        this.adapter = adapter;
        this.files = files;
    }

    @Override
    protected void onStart(EventHandler handler) throws Throwable {
        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            handler.onDeleting(i,file);
            if (!adapter.deleteFile(file)){
                handler.onFailed(i,file);
                return;
            }
        }
        handler.onSuccess();
    }

    public interface EventHandler extends BackstageTask.BaseEventHandler{
        void onDeleting(int index,String file);
        void onFailed(int index,String file);
        void onSuccess();
    }
}
