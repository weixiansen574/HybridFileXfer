package top.weixiansen574.async;

import android.os.Handler;
import android.os.Looper;

public class TaskManger {
    private static volatile Handler mainThreadHandler;
    private static Handler getUiThreadHandler() {
        if (mainThreadHandler == null) {
            synchronized (TaskManger.class) {
                if (mainThreadHandler == null) {
                    mainThreadHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return mainThreadHandler;
    }

    public static void postOnUiThread(Runnable runnable) {
        getUiThreadHandler().post(runnable);
    }
    public static void start(Runnable runnable){
        Thread thread = new Thread(runnable);
        thread.setName(runnable.getClass().getSimpleName());
        thread.start();
    }
    public static void execute(BackstageTask<?> backstageTask){
        start(backstageTask);
    }
}
