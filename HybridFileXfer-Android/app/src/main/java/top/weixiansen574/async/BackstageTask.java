package top.weixiansen574.async;

import android.os.Handler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public abstract class BackstageTask<T extends BackstageTask.BaseEventHandler> implements Runnable{
    private final T uiHandler;

    public BackstageTask(T uiHandler) {
        this.uiHandler = uiHandler;
    }

    protected abstract void onStart(T eventHandlerProxy) throws Throwable;

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        T proxyInstance = (T) Proxy.newProxyInstance(uiHandler.getClass().getClassLoader(),
                uiHandler.getClass().getInterfaces(),
                new SendToUiHandler(uiHandler));
        try {
            onStart(proxyInstance);
            TaskManger.postOnUiThread(uiHandler::onComplete);
        } catch (Throwable e) {
            TaskManger.postOnUiThread(() -> uiHandler.onError(e));
        }
    }


    public void execute() {
        TaskManger.execute(this);
    }

    public static class SendToUiHandler implements InvocationHandler{
        Object evHandler;

        public SendToUiHandler(Object evHandler) {
            this.evHandler = evHandler;
        }

        //当子线程调用代理对象的方法时，代理post到主线程，并调用事件处理器的相应方法
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class){
                return method.invoke(proxy,args);
            }
            TaskManger.postOnUiThread(() -> {
                try {
                    method.invoke(evHandler,args);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
            return null;
        }
    }

    public interface BaseEventHandler{
        default void onError(Throwable th) {
            throw new RuntimeException(th);
        }

        default void onComplete() {
        }
    }

}


