package top.weixiansen574.hybridfilexfer;

import android.content.Intent;
import android.os.IBinder;
import com.topjohnwu.superuser.ipc.RootService;

/** 由 libsu 托管的 Root I/O 服务，支持 Magisk、KernelSU、APatch 等直接 su 授权。 */
public class RootIOService extends RootService {
    @Override
    public IBinder onBind(Intent intent) {
        return new IOServiceImpl();
    }
    // RootService 进程生命周期由 libsu 管理，解绑时不直接调用 System.exit。
}
