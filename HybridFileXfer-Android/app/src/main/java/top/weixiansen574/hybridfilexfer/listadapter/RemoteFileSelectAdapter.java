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
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;

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
    protected String getDefaultDir(HFXServer server) {
        return server.getRemoteHomeDir();
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
                        Toast.makeText(context, R.string.chuang_jian_cheng_gong, Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(context, R.string.chuang_jian_shi_bai, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                onConnectionLost();
            }
        }).start();
    }

    private void onConnectionLost() {
        context.setResult(MainActivity.RESULT_CODE_SERVER_DISCONNECT);
        new AlertDialog.Builder(context)
                .setTitle(R.string.chuang_jian_shi_bai)
                .setMessage(R.string.kong_tong_dao_de_lian_jie_yi_duan_kai__)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> context.finish()).show();
    }
}
