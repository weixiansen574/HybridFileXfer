package top.weixiansen574.hybridfilexfer.droidserver;

import android.os.RemoteException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.ReceiveFileCall;
import top.weixiansen574.hybridfilexfer.core.SendFileCall;
import top.weixiansen574.hybridfilexfer.core.SpeedMonitorThread;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidserver.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class HFXServer {
    public static HFXServer instance;
    public static final String CLIENT_HEADER = "HFXC";
    public static final int VERSION_CODE = 300;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected final IIOService ioService;
    protected ServerSocketChannel serverSocketChannel;
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    protected int remoteFileSystem;

    public HFXServer(IIOService ioService) {
        this.ioService = ioService;
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount, StartServerCallback callback) {
        new StartServerTask(callback, this, port, interfaceList, localBufferCount, remoteBufferCount).execute();
    }

    public void closeServerSocket() {
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
        ReadFileCall readFileCall = new DroidReadFileCall(ioService, buffers, files, localDir, remoteDir, connections.size());
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
        } catch (IOException e){
            speedMonitorThread.cancel();
            callback.onIncomplete();
            return;
        }
        speedMonitorThread.cancel();

        if (!complete) {
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            return;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                speedMonitorThread.cancel();
                callback.onIncomplete();
                return;
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
            return;
        }
        
        callback.onComplete(totalUploadTraffic, System.currentTimeMillis() - startTime);
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
        ctChannel.writeInt(files.size());
        for (RemoteFile file : files) {
            ctChannel.writeUTF(file.getPath());
        }
        ctChannel.writeUTF(localDir.path);
        ctChannel.writeInt(localDir.fileSystem);
        ctChannel.writeUTF(remoteDir.path);
        WriteFileCall writeFileCall = new DroidWriteFileCall(buffers,connections.size(),ioService);
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i,connection,writeFileCall,callback));
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
            return;
        }

        for (FutureTask<Void> task : transferTasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                callback.onIncomplete();
                return;
            }
        }
        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(totalDownloadTraffic, System.currentTimeMillis() - startTime);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
        ctChannel.writeUTF(path);
        int listSize = ctChannel.readInt();
        if (listSize == -1) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
        for (int i = 0; i < listSize; i++) {
            RemoteFile remoteFile = new RemoteFile(
                    ctChannel.readUTF(),//name
                    ctChannel.readUTF(),//path
                    ctChannel.readLong(),//lastModified
                    ctChannel.readLong(),//size
                    ctChannel.readBoolean()//isDirectory
            );
            remoteFiles.add(remoteFile);
        }
        return remoteFiles;
    }

    public boolean deleteRemoteFile(String file) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
        ctChannel.writeUTF(file);
        return ctChannel.readBoolean();
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.MKDIR);
        ctChannel.writeUTF(parent);
        ctChannel.writeUTF(child);
        return ctChannel.readBoolean();
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            remoteFiles.addAll(ioService.getAndRemoveFileListSlice(chunkId));
        }
        return remoteFiles;
    }

    public List<String> getConnectionListINames() {
        List<String> names = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            names.add(connection.iName);
        }
        return names;
    }
}
