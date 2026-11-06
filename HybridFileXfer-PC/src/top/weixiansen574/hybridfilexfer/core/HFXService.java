package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    /**
     * 302: 每个物理网卡开多条 TCP 连接（原来是一张网卡一条）。
     * <p>
     * 实测（同一台机、同一条 wlan0、裸 TCP 无应用层协议）：
     * <pre>
     *   1 条流  156.6 MB/s
     *   2 条流  202.3 MB/s
     *   4 条流  233.7 MB/s
     *   8 条流  277.3 MB/s
     * </pre>
     * 单条 TCP 流受 cwnd 与空口调度限制，喂不满 WiFi 的多队列/A-MPDU 聚合能力，
     * 一张网卡一条连接等于把链路潜力浪费掉三分之一以上。
     * <p>
     * 303: 连接数从"所有网卡同一个倍数"改为"逐张网卡下发"——WiFi 8 条、USB_ADB 1 条。
     * 握手里的 int 由单值变成每张网卡一个，字节布局变了，故版本号必须同步。
     * <p>
     * 310: 3.1 版传输层优化——块大小 1MB→2MB、发送端改 sendfile 零拷贝、
     * 块头 7 次 write 合并为 1 次。数据块布局与 303 一致，版本号随应用版本对齐。
     */
    public static final int VERSION_CODE = 310;

    /**
     * 每张网卡开几条 TCP 连接。握手时由客户端逐张网卡下发，服务端只照着 accept，
     * 因此策略集中在客户端这一处，服务端不需要知道网卡类型。
     * <p>
     * WiFi 上单流受 cwnd 和空口调度限制，加流有效，但存在拐点：
     * 实测（下行、裸 TCP）1/2/4/8 流 = 156/202/233/277 MB/s。
     * 而手机发送方向（上行）实测 6 流与 8 流总数同为 255~259，加流已无收益，
     * 反而多占 CPU 和空口竞争，故取 6。
     * <p>
     * USB_ADB 走 adb forward 隧道（手机侧是 USB 2.0，40MB/s 封顶），单流 35.5、
     * 6 流 40.8，已经贴着物理上限，取 2 条即可吃满，再多只是白养隧道线程。
     */
    private static final int CONNECTIONS_WIFI = 6;
    private static final int CONNECTIONS_USB_ADB = 2;
    private static final int CONNECTIONS_FALLBACK = 4;

    public static int connectionsFor(String iName) {
        if (iName.startsWith("USB_ADB")) {
            return CONNECTIONS_USB_ADB;
        }
        if (iName.startsWith("wlan") || iName.startsWith("ap") || iName.startsWith("eth")) {
            return CONNECTIONS_WIFI;
        }
        return CONNECTIONS_FALLBACK;
    }
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;

    protected boolean sendFiles(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask);
        readThread.setName("FileRead");
        readThread.start();
        //另开一个线程读取传输流量信息，1秒一次
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }

        //其中一条通道断掉，可能控制器通道也一起跟着断了
        boolean complete;
        try {
            //等待客户端接收成功或者写入到硬盘时发生IO错误
            complete = ctChannel.readBoolean();
        } catch (IOException e) {
            speedMonitorThread.cancel();
            callback.onIncomplete();
            return false;
        }
        speedMonitorThread.cancel();

        if (!complete) {
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            return true;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                callback.onIncomplete();
                return false;
            }
        }

        long totalUploadTraffic = 0;
        for (TransferConnection connection : connections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(ex);
            callback.onReadFileError(ex);
            return true;
        }

        callback.onComplete(true,totalUploadTraffic, System.currentTimeMillis() - startTime);
        return true;
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread thread = new Thread(writeFileTask);
        thread.setName("FileWrite");
        thread.start();
        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            speedMonitorThread.cancel();
            Throwable cause = e.getCause();
            ctChannel.writeBoolean(false);
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeUTF(ex);
            callback.onWriteFileError(ex);
            return true;
        }

        for (FutureTask<Void> task : transferTasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                //此时没有连同控制器通道一起断掉，要通知对方，写线程没问题（对方的传输线程通道已出问题）
                ctChannel.writeBoolean(true);
                callback.onIncomplete();
                return false;
            }
        }
        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false,totalDownloadTraffic, System.currentTimeMillis() - startTime);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount);

}