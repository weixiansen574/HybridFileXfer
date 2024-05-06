package top.weixiansen574.hybridfilexfer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class TransferServices extends Service {
    public TransferServiceBinder binder;

    public TransferServices() {
        this.binder = new TransferServiceBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {binder.destroy();} catch (RemoteException ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

}