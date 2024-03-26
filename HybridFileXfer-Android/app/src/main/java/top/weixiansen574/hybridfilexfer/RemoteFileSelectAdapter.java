package top.weixiansen574.hybridfilexfer;

import android.app.Activity;
import android.content.Context;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;

import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;

public class RemoteFileSelectAdapter extends IIServiceFileSelectAdapter{
    public RemoteFileSelectAdapter(Activity context, View.OnTouchListener onTouchListener, Toolbar fileSelectToolbar, FrameLayout frameLayout, RecyclerView recyclerView, ITransferService service) {
        super(context, onTouchListener, fileSelectToolbar, frameLayout, recyclerView, service);
        currentDir = "/";
        cd(currentDir);

    }

    @Override
    public List<ParcelableRemoteFile> listTargetFiles(String path) throws RemoteException {
        return service.listClientFiles(path);
    }

}
