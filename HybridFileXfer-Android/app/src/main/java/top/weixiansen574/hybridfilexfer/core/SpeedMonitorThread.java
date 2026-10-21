package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

public class SpeedMonitorThread extends Thread {
    private volatile boolean isRun = true;
    private final List<TransferConnection> connections;
    private final TransferFileCallback callback;

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback) {
        this.connections = connections;
        this.callback = callback;
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        while (isRun){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            List<TrafficInfo> trafficInfoList = new ArrayList<>();
            for (TransferConnection channel : connections) {
                trafficInfoList.add(channel.resetCurrentTrafficInfo());
            }
            try { callback.onSpeedInfo(trafficInfoList); }
            catch (RuntimeException ignored) { /* A failed observer does not stop transfer monitoring. */ }
        }
    }

    public void cancel(){
        isRun = false;
        interrupt();
    }
}
