package top.weixiansen574.hybridfilexfer;

import android.content.Intent;
import android.os.IBinder;

import com.topjohnwu.superuser.ipc.RootService;

/**
 * 通过libsu的RootService在root进程中运行IOServiceImpl。
 * 支持KernelSU、Magisk、APatch等所有提供su二进制的root方案。
 */
public class RootIOService extends RootService {
    private IOServiceImpl service;

    @Override
    public IBinder onBind(Intent intent) {
        service = new IOServiceImpl();
        return service;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (service != null) {
            service.destroy();
        }
        return false;
    }
}
