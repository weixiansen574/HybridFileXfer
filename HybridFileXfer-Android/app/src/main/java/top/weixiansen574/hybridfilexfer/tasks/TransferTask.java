package top.weixiansen574.hybridfilexfer.tasks;

import java.util.List;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;

public abstract class TransferTask<T extends TransferTask.EventHandler > extends BackstageTask<T> {
    protected HFXServer server;
    protected SpeedMonitorThread speedMonitorThread;

    public TransferTask(T uiHandler, HFXServer server) {
        super(uiHandler);
        this.server = server;
    }

    protected void startEventReceiveThread(EventHandler handler){
        Thread erThread = new Thread(() -> {
            while (true) {
                TransferEvent transferEvent = server.getCurrentTransferEvent();
                if (transferEvent == null) {
                    break;
                }
                handler.onTransferEvent(transferEvent);
            }
        });
        erThread.setName("EventReceive");
        erThread.start();
    }

    public void startSpeedMonitorThread(EventHandler handler){
        speedMonitorThread = new SpeedMonitorThread(handler, server);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
    }

    public void cancelSpeedMonitorThread(){
        speedMonitorThread.cancel();
    }



    private static class SpeedMonitorThread extends Thread {
        EventHandler eventHandler;
        HFXServer server;
        boolean isRun = true;

        public SpeedMonitorThread(EventHandler eventHandler, HFXServer server) {
            this.eventHandler = eventHandler;
            this.server = server;
        }

        @Override
        public void run() {
            while (isRun) {
                eventHandler.onSpeedInfo(server.getTrafficInfoList());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            //最后要归零,使其完全吐出
            eventHandler.onSpeedInfo(server.getTrafficInfoList());
            eventHandler.onSpeedInfo(server.getTrafficInfoList());
        }

        public void cancel() {
            this.isRun = false;
        }
    }

    public interface EventHandler extends BaseEventHandler{
        void onTransferEvent(TransferEvent transferEvent);

        void onSpeedInfo(List<TrafficInfo> infoList);

        void onTransferFailed(String exceptionMessage);

        void onTransferOver();
    }
}
