package top.weixiansen574.hybridfilexfer.listadapter;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class LocalFileSelectAdapter extends FileSelectAdapter {
    public LocalFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener, server);
    }

    @Override
    public List<RemoteFile> listFiles(String path) {
        return server.listLocalFiles(path);
    }

    @Override
    protected void handleListException(Throwable th) {

    }

    @Override
    protected String getDefaultDir() {
        return "/storage/emulated/0/";
    }

    @Override
    public boolean deleteFile(String file) {
        return server.deleteLocalFile(file);
    }

    @Override
    public void mkdir(String parent, String child) {
        if (server.createLocalDir(parent,child)) {
            Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show();
            refresh();
        } else {
            Toast.makeText(context, "创建失败", Toast.LENGTH_SHORT).show();
        }
    }
}
