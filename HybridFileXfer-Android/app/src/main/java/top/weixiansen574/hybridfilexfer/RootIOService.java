package top.weixiansen574.hybridfilexfer;

import android.content.Intent;
import android.os.IBinder;
import com.topjohnwu.superuser.ipc.RootService;

/** The same I/O binder, hosted by libsu for Magisk, KernelSU and other su providers. */
public class RootIOService extends RootService {
    @Override
    public IBinder onBind(Intent intent) {
        return new IOServiceImpl();
    }
    // libsu owns process termination after the final client unbinds. Do not System.exit here.
}
