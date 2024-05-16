package top.weixiansen574.hybridfilexfer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public class LocalFileSelectAdapter extends IIServiceFileSelectAdapter{
    public LocalFileSelectAdapter(Activity context, View.OnTouchListener onTouchListener, Toolbar fileSelectToolbar, FrameLayout frameLayout, View listInView, ITransferService service) {
        super(context, onTouchListener, fileSelectToolbar, frameLayout, listInView, service);
        currentDir = "/storage/emulated/0/";

        cd(currentDir);
    }

    @Override
    public List<ParcelableRemoteFile> listTargetFiles(String path) throws RemoteException {
        ArrayList<ParcelableRemoteFile> files = new ArrayList<>();
        int size = service.listLocalFiles(path);
        for (int i = 0; i < size; i++) {
            files.addAll(service.pollLocalFiles());
        }
        return files;
    }
}
