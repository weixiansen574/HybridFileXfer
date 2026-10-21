package top.weixiansen574.hybridfilexfer;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

public class IOService extends Service {
    private IOServiceImpl service;
    private static Shizuku.UserServiceArgs userServiceArgs;
    public IOService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        service = new IOServiceImpl();
        return service;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 本服务运行在独立的 :IOService 进程中，退出时直接终止进程以释放残留的文件锁。
        if (service != null) service.destroy();
    }

    public static synchronized Shizuku.UserServiceArgs getUserServiceArgs(Context context){
        if (userServiceArgs == null) {
            userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, IOServiceImpl.class))
                    .daemon(false)
                    .processNameSuffix("IOService")
                    .debuggable(false)
                    .version(BuildConfig.VERSION_CODE);
        }
        return userServiceArgs;
    }
}
