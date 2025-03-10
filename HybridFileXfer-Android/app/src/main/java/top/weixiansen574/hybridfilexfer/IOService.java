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
        service.destroy();
    }

    public static synchronized Shizuku.UserServiceArgs getUserServiceArgs(Context context){
        if (userServiceArgs == null) {
            userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, IOServiceImpl.class))
                    .daemon(true)
                    .processNameSuffix("IOService")
                    .debuggable(false)
                    .version(1);
        }
        return userServiceArgs;
    }
}