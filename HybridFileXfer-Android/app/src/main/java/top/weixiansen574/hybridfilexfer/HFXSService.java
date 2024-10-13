package top.weixiansen574.hybridfilexfer;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import rikka.shizuku.Shizuku;
import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.HFXService;

public class HFXSService extends Service {
    public static HFXServer server;
    private static Shizuku.UserServiceArgs userServiceArgs;
    private HFXService service;
    public HFXSService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        service = new HFXService();
        return service;
    }

    @Override
    public void onDestroy() {
        System.out.println("服务关闭");
        new Thread(() -> service.destroy()).start();

    }

    public static synchronized Shizuku.UserServiceArgs getUserServiceArgs(Context context){
        if (userServiceArgs == null) {
            userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, HFXService.class))
                    .daemon(false)
                    .processNameSuffix("HFXService")
                    .debuggable(false)
                    .version(1);
        }
        return userServiceArgs;
    }
}