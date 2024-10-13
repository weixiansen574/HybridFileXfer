package top.weixiansen574.hybridfilexfer.core;

import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.IHFXService;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.SocketConnectStatus;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;

public class HFXServer {
    IHFXService service;
    boolean isFailed = false;

    public HFXServer(IHFXService service) {
        this.service = service;
    }

    public boolean startServer(int port) {
        try {
            return service.bind(port);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean waitConnect(List<ServerNetInterface> interfaceList, WaitConnectCallBack callBack) throws RemoteException {
        t:
        while (true) {
            boolean success = service.waitToConnect(interfaceList);
            if (!success) {
                callBack.onServerClose();
                return false;
            }
            for (int i = 0; i < interfaceList.size(); i++) {
                SocketConnectStatus socketConnectStatus = service.acceptTransferChannel();
                if (socketConnectStatus == null) {//发生异常
                    continue t;
                }
                if (socketConnectStatus.success) {
                    callBack.onAccepted(socketConnectStatus.name);
                } else {
                    callBack.onAcceptFailed(socketConnectStatus.name);
                    continue t;
                }
            }
            return true;
        }

    }

    public interface WaitConnectCallBack {
        void onAccepted(String name);

        void onAcceptFailed(String name);

        void onServerClose();
    }

    public void closeServer() {
        try {
            service.closeServer();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public List<String> getChannelListINames() {
        try {
            return service.getChannelListINames();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void exitService() {
        try {
            service.destroy();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public List<RemoteFile> listLocalFiles(String path) {
        try {
            int[] chunkIds = service.listLocalFiles(path);
            if (chunkIds == null) {
                return null;
            }
            List<RemoteFile> remoteFiles = new ArrayList<>();
            for (int chunkId : chunkIds) {
                remoteFiles.addAll(service.getAndRemoveLocalFileListSlice(chunkId));
            }
            return remoteFiles;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public List<RemoteFile> listClientFiles(String path) throws ControllerConnectionClosedException {
        try {
            if (!service.writeListFiles(path)) {
                throw new ControllerConnectionClosedException();
            }
            int[] chunkIds = service.listClientFiles();
            if (chunkIds == null) {
                return null;
            }
            List<RemoteFile> remoteFiles = new ArrayList<>();
            for (int chunkId : chunkIds) {
                remoteFiles.addAll(service.getAndRemoveRemoteFileListSlice(chunkId));
            }
            return remoteFiles;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public TransferEvent getCurrentTransferEvent() {
        try {
            return service.getCurrentTransferEvent();
        } catch (RemoteException e) {
            return null;
        }
    }

    public List<TrafficInfo> getTrafficInfoList() {
        try {
            return service.getTrafficInfoList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean requestRemoteReceive() {
        try {
            return service.requestRemoteReceive();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String sendFilesToRemote(List<String> files, String localDir, String remoteDir) {
        try {
            return service.sendFilesToRemote(files, localDir, remoteDir);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean requestRemoteSend(List<String> files, String localDir, String remoteDir){
        try {
            return service.requestRemoteSend(files, localDir, remoteDir);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String receiveFiles(){
        try {
            return service.receiveFiles();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteLocalFile(String file){
        try {
            return service.deleteLocalFile(file);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteRemoteFile(String file){
        try {
            return service.deleteRemoteFile(file);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean createLocalDir(String parent,String child){
        try {
            return service.createLocalDir(parent, child);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean createRemoteDir(String parent,String child){
        try {
            return service.createRemoteDir(parent, child);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void markFailed(){
        isFailed = true;
    }

    public boolean isFailed() {
        return isFailed;
    }

    /*public synchronized void sendFilesToRemote(List<File> files, File localDir, String remoteDir) {

            JobPool pool = new JobPool(localDir, remoteDir, files);
            List<Thread> threads = new ArrayList<>(channels.size());
            for (TransferChannel channel : channels) {
                Thread thread = new Thread(() -> {
                    try {
                        channel.send(pool);
                    } catch (InterruptedException | IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                thread.setName(channel.name+"_send");
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            System.out.println("传输完成");

        }

        public synchronized void sendFilesToSelf(List<String> files, String remoteDir, String localDir) {

        }*/
    public static class ControllerConnectionClosedException extends Exception {
    }


}
