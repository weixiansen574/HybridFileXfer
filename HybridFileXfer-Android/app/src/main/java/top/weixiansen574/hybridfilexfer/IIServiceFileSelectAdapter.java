package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import top.weixiansen574.async.EventMessage;
import top.weixiansen574.hybridfilexfer.async.CDParentTask;
import top.weixiansen574.hybridfilexfer.async.CDTask;
import top.weixiansen574.hybridfilexfer.async.IIServiceRequestHandler;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public abstract class IIServiceFileSelectAdapter extends FileSelectAdapter<ParcelableRemoteFile> implements IIServiceRequestHandler.ErrorHandler {
    protected String currentDir;
    protected List<ParcelableRemoteFile> currentFiles;
    protected ITransferService service;

    public IIServiceFileSelectAdapter(Activity context, View.OnTouchListener onTouchListener, Toolbar fileSelectToolbar, FrameLayout frameLayout, RecyclerView recyclerView, ITransferService service) {
        super(context, onTouchListener, fileSelectToolbar, frameLayout, recyclerView);
        this.service = service;
    }

    @Override
    protected String getFileName(ParcelableRemoteFile item) {
        return item.getName();
    }

    @Override
    protected String getPath(ParcelableRemoteFile item) {
        return item.getPath();
    }

    @Override
    protected boolean isDir(ParcelableRemoteFile item) {
        return item.isDirectory();
    }

    @Override
    protected ParcelableRemoteFile getItem(int position) {
        return currentFiles.get(position);
    }

    @Override
    protected List<ParcelableRemoteFile> getAllItems() {
        return currentFiles;
    }

    public abstract List<ParcelableRemoteFile> listTargetFiles(String path) throws RemoteException;

    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void cd(String path) {
        System.out.println("cd " + path);
        enterLoadingState();
        CDHandler handler = new CDHandler(this);
        new CDTask(handler, this, path).execute();
    }

    private static class CDHandler extends CDTask.EventHandle {

        private final IIServiceFileSelectAdapter adapter;

        public CDHandler(IIServiceFileSelectAdapter adapter) {
            super(adapter);
            this.adapter = adapter;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        protected void handleEvent(EventMessage message) {
            switch (message.getWhat()) {
                case SUCCESS:
                    adapter.currentFiles = message.getObject(0, List.class);
                    adapter.currentDir = message.getObject(1, String.class);
                    adapter.notifyDataSetChanged();
                    adapter.exitLoadingState();
                    break;
                case PERMISSION_DENIED:
                    adapter.exitLoadingState();
                    Toast.makeText(adapter.context, "无权访问", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    }


    @Override
    public String getCurrentDir() {
        return currentDir;
    }

    @Override
    protected long getFileDate(ParcelableRemoteFile item) {
        return item.getLastModified();
    }

    @Override
    protected long getFileSize(ParcelableRemoteFile item) {
        return item.getSize();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void cdParent() {
        enterLoadingState();
        CDParentHandler handler = new CDParentHandler(this);
        new CDParentTask(handler, this, Utils.replaceBackslashToSlash(currentDir)).execute();
    }

    @Override
    public void handleRemoteException(RemoteException e) {
        if (e instanceof DeadObjectException) {
            new AlertDialog.Builder(context)
                    .setTitle("发生异常")
                    .setMessage("服务端已终止（网络连接中断或服务进程被杀），请重新连接！")
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> {
                        context.finish();
                    }).show();
        } else {
            Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void handleOtherExceptions(Throwable th) {

    }

    private static class CDParentHandler extends CDParentTask.EventHandler {

        private final IIServiceFileSelectAdapter adapter;

        public CDParentHandler(IIServiceFileSelectAdapter adapter) {
            super(adapter);
            this.adapter = adapter;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        protected void handleEvent(EventMessage message) {
            switch (message.getWhat()) {
                case SUCCESS:
                    adapter.currentFiles = message.getObject(0, List.class);
                    adapter.currentDir = message.getObject(1, String.class);
                    adapter.notifyDataSetChanged();
                    break;
                case PERMISSION_DENIED:
                    Toast.makeText(adapter.context, "无法前往上级文件夹，因为没有权限", Toast.LENGTH_SHORT).show();
                    break;
                case PARENT_DIR_NOT_FILES:
                    Toast.makeText(adapter.context, "上级文件夹无法列出文件数为空，不切换到父目录，不然你会回不来！", Toast.LENGTH_SHORT).show();
                    break;
                case THIS_IS_THE_LAST_PAGE:
                    Toast.makeText(adapter.context, "已经到最后一页了", Toast.LENGTH_SHORT).show();
                    break;
            }
            adapter.exitLoadingState();
        }
    }


    @Override
    public int getItemCount() {
        return currentFiles.size();
    }


}
