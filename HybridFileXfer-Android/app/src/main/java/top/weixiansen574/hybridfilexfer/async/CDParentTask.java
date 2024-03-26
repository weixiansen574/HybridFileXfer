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
                eventHandler.sendEmptyEventMessage(EventHandler.PERMISSION_DENIED);
                //Toast.makeText(context, "无法前往上级文件夹，因为没有权限", Toast.LENGTH_SHORT).show();
            } else if (files.size() > 0) {
                //currentDir = parentPath;
                //currentFiles = files;
                Utils.sortFiles(files);
                eventHandler.sendEventMessage(EventHandler.SUCCESS,files,parentPath);
            } else {
                eventHandler.sendEmptyEventMessage(EventHandler.PARENT_DIR_NOT_FILES);
                //Toast.makeText(context, "上级文件夹无法列出文件数为空，不切换到父目录，不然你会回不来！", Toast.LENGTH_SHORT).show();
            }
        } else {
            eventHandler.sendEmptyEventMessage(EventHandler.THIS_IS_THE_LAST_PAGE);
            //Toast.makeText(context, "已经到最后一页了", Toast.LENGTH_SHORT).show();
        }
    }

    public static abstract class EventHandler extends IIServiceRequestHandler {
        public static final int SUCCESS = 0;
        public static final int PERMISSION_DENIED = 1;
        public static final int PARENT_DIR_NOT_FILES = 2;
        public static final int THIS_IS_THE_LAST_PAGE = 3;

        public EventHandler(ErrorHandler errorHandler) {
            super(errorHandler);
        }
    }
}
