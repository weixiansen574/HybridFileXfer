package top.weixiansen574.hybridfilexfer;

import android.app.Activity;
import android.content.Context;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public class RemoteFileSelectAdapter extends IIServiceFileSelectAdapter{
    public RemoteFileSelectAdapter(Activity context, View.OnTouchListener onTouchListener, Toolbar fileSelectToolbar, FrameLayout frameLayout, View listInView, ITransferService service) {
        super(context, onTouchListener, fileSelectToolbar, frameLayout, listInView, service);
        currentDir = "/";
        cd(currentDir);

    }

    @Override
    public List<ParcelableRemoteFile> listTargetFiles(String path) throws RemoteException {
        ArrayList<ParcelableRemoteFile> files = new ArrayList<>();
        int size = service.listClientFiles(path);
        for (int i = 0; i < size; i++) {
            files.addAll(service.pollRemoteFiles());
        }
        return files;
    }

}
