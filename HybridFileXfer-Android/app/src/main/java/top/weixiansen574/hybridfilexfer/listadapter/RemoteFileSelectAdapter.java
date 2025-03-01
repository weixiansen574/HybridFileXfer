package top.weixiansen574.hybridfilexfer.listadapter;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

import top.weixiansen574.hybridfilexfer.MainActivity;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.droidserver.HFXServer;
//TODO 国际化
public class RemoteFileSelectAdapter extends FileSelectAdapter {

    public RemoteFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener, server);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws IOException {
        return server.listClientFiles(path);
    }

    @Override
    protected void handleListException(Throwable th) {
        onConnectionLost();
    }

    @Override
    protected String getDefaultDir() {
        return "/";
    }

    @Override
    protected int getFileSystem(HFXServer server) {
        return server.getRemoteFileSystem();
    }


    @Override
    public boolean deleteFile(String file) throws IOException {
        return server.deleteRemoteFile(file);
    }

    @Override
    public void mkdir(String parent, String child) {
        new Thread(() -> {
            try {
                boolean success = server.createRemoteDir(parent, child);
                context.runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(context, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                onConnectionLost();
            }
        }).start();
    }

    private void onConnectionLost(){
        context.setResult(MainActivity.RESULT_CODE_SERVER_DISCONNECT);
        new AlertDialog.Builder(context)
                .setTitle("连接异常")
                .setMessage("控通道的连接已断开，请退出后重新连接！")
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> context.finish()).show();
    }
}
