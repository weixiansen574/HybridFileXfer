package top.weixiansen574.async;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    public static void start(Runnable runnable){
        executorService.execute(runnable);
    }
    public static void execute(BackstageTask<?> backstageTask){
        start(backstageTask);
    }
}
