package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.l4digital.fastscroll.FastScroller;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.async.CDParentTask;
import top.weixiansen574.hybridfilexfer.async.CDTask;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public abstract class IIServiceFileSelectAdapter extends FileSelectAdapter<ParcelableRemoteFile> implements FastScroller.SectionIndexer {
    protected String currentDir;
    protected List<ParcelableRemoteFile> currentFiles = new ArrayList<>();
    protected ITransferService service;

    public IIServiceFileSelectAdapter(Activity context, View.OnTouchListener onTouchListener, Toolbar fileSelectToolbar, FrameLayout frameLayout, View listInView, ITransferService service) {
        super(context, onTouchListener, fileSelectToolbar, frameLayout, listInView);
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

    private static class CDHandler implements CDTask.EventHandle {

        private final IIServiceFileSelectAdapter adapter;

        public CDHandler(IIServiceFileSelectAdapter adapter) {
            this.adapter = adapter;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onSuccess(List<ParcelableRemoteFile> files, String path) {
            adapter.currentFiles = files;
            adapter.currentDir = path;
            adapter.notifyDataSetChanged();
            adapter.exitLoadingState();
        }

        @Override
        public void onPermissionDenied() {
            adapter.exitLoadingState();
            Toast.makeText(adapter.context, "无权访问", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onError(Throwable th) {
            adapter.handleIIServiceExceptions(th);
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

    public void handleIIServiceExceptions(Throwable e) {
        e.printStackTrace();
        if (e instanceof DeadObjectException) {
            new AlertDialog.Builder(context)
                    .setTitle("发生异常")
                    .setMessage(e.getMessage())
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> {
                        context.finish();
                    }).show();
        } else {
            Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show();
        }
    }
    private static class CDParentHandler implements CDParentTask.EventHandler {

        private final IIServiceFileSelectAdapter adapter;

        public CDParentHandler(IIServiceFileSelectAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public void onError(Throwable th) {

        }

        @Override
        public void onComplete() {
            adapter.exitLoadingState();
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onSuccess(List<ParcelableRemoteFile> files, String parentPath) {
            adapter.currentFiles = files;
            adapter.currentDir = parentPath;
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onPermissionDenied() {
            Toast.makeText(adapter.context, "无法前往上级文件夹，因为没有权限", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onParentDirNotFiles() {
            Toast.makeText(adapter.context, "上级文件夹无法列出文件数为空，不切换到父目录，不然你会回不来！", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onThisIsTheLastPage() {
            Toast.makeText(adapter.context, "已经到最后一页了", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    public int getItemCount() {
        return currentFiles.size();
    }

    @Override
    public CharSequence getSectionText(int position) {
        return String.valueOf(getItem(position).getName().charAt(0));
    }
}
