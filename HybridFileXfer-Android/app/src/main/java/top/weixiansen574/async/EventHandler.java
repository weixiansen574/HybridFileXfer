package top.weixiansen574.async;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

public abstract class EventHandler extends Handler {
    public EventHandler() {
        super();
    }

    public EventHandler(@NonNull Looper looper) {
        super(looper);
    }

    public void sendEventMessage(EventMessage message) {
        post(() -> handleEvent(message));
    }

    public void sendEventMessage(int what,Object... objects){
        post(() -> handleEvent(new EventMessage(what,objects)));
    }

    public void sendEmptyEventMessage(int what){
        post(() -> handleEvent(new EventMessage(what)));
    }

    public void sendError(Throwable th) {
        post(() -> handleError(th));
    }

    protected abstract void handleEvent(EventMessage message);

    protected abstract void handleError(Throwable th);

}
