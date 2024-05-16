package top.weixiansen574.hybridfilexfer;

import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.FileTransferServer;
import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.droidcore.EndCommandFTEvent;
import top.weixiansen574.hybridfilexfer.droidcore.Error;

import top.weixiansen574.hybridfilexfer.droidcore.ParcelableFileTransferEvent;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableTransferredBytesInfo;

public class TransferServiceBinder extends ITransferService.Stub {
    public static final String TAG = "TransferServiceBinder";
    FileTransferServer fileTransferServer;
    public LinkedList<ArrayList<ParcelableRemoteFile>> localFileQueue;
    public LinkedList<ArrayList<ParcelableRemoteFile>> remoteFileQueue;
    public static final int SLICE_SIZE = 1000;


    @Override
    public void destroy() throws RemoteException {
        System.exit(0);
    }

    @Override
    public void exit() throws RemoteException {

    }

    //先开线程执行停止，使其阻塞到结束，结束后再执行unbind使其执行destroy()
    public synchronized void stopServer(){
        fileTransferServer.stopServer();
        notifyAll();
    }

    @Override
    public synchronized void waitingForDied() throws RemoteException {
        try {
            wait();
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public Error startServer() throws RemoteException {
        try {
            fileTransferServer.startServer();
        } catch (IOException e) {
            e.printStackTrace();
            if (e instanceof BindException){
                return new Error(Error.CODE_PORT_IS_OCCUPIED,e.getMessage());
            } else {
                return new Error(Error.CODE_IOEXCEPTION,e.toString());
            }
        }
        return null;
    }

    public TransferServiceBinder() {
        fileTransferServer = new FileTransferServer();
    }

    @Override
    public void transferToPc(List<String> files, String localDir, String remoteDir) throws RemoteException {
        List<File> fileList = new ArrayList<>();
        for (String file : files) {
            fileList.add(new File(file));
        }
        fileTransferServer.transferToClient(fileList,new File(localDir),remoteDir);
    }

    public void transferToMe(List<String> files, String remoteDir, String localDir) throws RemoteException {
        try {
            fileTransferServer.transferToMe(files,remoteDir,localDir);
        } catch (IOException e) {
            throw new RemoteException(e.toString());
        }
    }

    @Override
    public int listClientFiles(String path) throws RemoteException {
        try {
            ArrayList<RemoteFile> remoteFiles = fileTransferServer.listClientFiles(path);

            int count = 0;
            remoteFileQueue = new LinkedList<>();
            ArrayList<ParcelableRemoteFile> fileArraySlice = new ArrayList<>();

            for (RemoteFile remoteFile : remoteFiles) {
                count++;
                fileArraySlice.add(new ParcelableRemoteFile(remoteFile));
                if (count >= SLICE_SIZE){
                    remoteFileQueue.add(fileArraySlice);
                    fileArraySlice = new ArrayList<>();
                    count=0;
                }
            }
            remoteFileQueue.add(fileArraySlice);
            return remoteFileQueue.size();
        } catch (IOException e) {
            throw new RemoteException(e.toString());
        }
    }

    @Override
    public ArrayList<ParcelableRemoteFile> pollRemoteFiles() throws RemoteException {
        if (remoteFileQueue == null){
            return null;
        }
        return remoteFileQueue.poll();
    }

    @Override
    public ParcelableFileTransferEvent getNextFileTransferEvent() throws RemoteException {
        try {
            FileTransferEvent nextTransferEvent = fileTransferServer.getNextTransferEvent();
            if (nextTransferEvent instanceof EndCommandFTEvent){
                System.out.println("停止获取下一个事件");
                return null;
            }
            return new ParcelableFileTransferEvent(nextTransferEvent);
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.out.println("发生异常，线程被中断");
            return null;
        }
    }

    @Override
    public ParcelableTransferredBytesInfo getTransferredBytesInfo() throws RemoteException {
        return new ParcelableTransferredBytesInfo(fileTransferServer.getTransferredBytesInfo());
    }
    @Override
    public void stopGetNextEvent(){
        fileTransferServer.fileTransferEvents.add(new EndCommandFTEvent());
    }

    @Override
    public int listLocalFiles(String path) throws RemoteException{
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null){
            return -1;
        }
        int count = 0;
        localFileQueue = new LinkedList<>();
        ArrayList<ParcelableRemoteFile> fileArraySlice = new ArrayList<>();
        for (File file : files) {
            count++;
            ParcelableRemoteFile parcelableRemoteFile = new ParcelableRemoteFile(file);
            fileArraySlice.add(parcelableRemoteFile);
            if (count >= SLICE_SIZE){
                localFileQueue.add(fileArraySlice);
                fileArraySlice = new ArrayList<>();
                count=0;
            }
        }
        localFileQueue.add(fileArraySlice);
        return localFileQueue.size();
    }

    @Override
    public List<ParcelableRemoteFile> pollLocalFiles() throws RemoteException {
        if (localFileQueue == null){
            return null;
        }
        return localFileQueue.poll();
    }

    @Override
    public ParcelableRemoteFile getParentFile(String path) throws RemoteException {
        String parent = new File(path).getParent();
        Log.i(TAG,parent+"");
        File parentFile = new File(path).getParentFile();
        Log.i(TAG,parentFile+"");
        if (parentFile == null) {
            return null;
        }
        return new ParcelableRemoteFile(parentFile);
    }

}
