package top.weixiansen574.async;

public abstract class BackstageTask<T extends  EventHandler> implements Runnable{
    private final T handle;
    public BackstageTask(T handle){
        this.handle = handle;
    }

    @Override
    public void run(){
        try {
            onStart(handle);
        } catch (Throwable e) {
            handle.sendError(e);
        }
    }
    public void execute(){TaskManger.execute(this);}
    protected abstract void onStart(T eventHandler) throws Throwable;
    protected void sleep(long time){
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
