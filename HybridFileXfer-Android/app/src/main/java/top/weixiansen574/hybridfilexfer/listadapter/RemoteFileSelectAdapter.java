package top.weixiansen574.hybridfilexfer.listadapter;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.R;

public class RemoteFileSelectAdapter extends FileSelectAdapter {

    public RemoteFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener, server);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws HFXServer.ControllerConnectionClosedException {
        return server.listClientFiles(path);
    }

    @Override
    protected void handleListException(Throwable th) {
        server.markFailed();
        if (th instanceof HFXServer.ControllerConnectionClosedException) {
            new AlertDialog.Builder(context)
                    .setTitle("列出电脑文件失败")
                    .setMessage("控通道的连接已断开，请重新连接！")
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, (dialog, which) -> context.finish()).show();
        }
    }

    @Override
    protected String getDefaultDir() {
        return "/";
    }

    @Override
    public boolean deleteFile(String file) {
        return server.deleteRemoteFile(file);
    }

    @Override
    public void mkdir(String parent, String child) {
        new Thread(() -> {
            boolean success = server.createRemoteDir(parent, child);
            context.runOnUiThread(() -> {
                if (success) {
                    Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(context, "创建失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
}
