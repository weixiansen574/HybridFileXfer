package top.weixiansen574.hybridfilexfer;

import android.app.Instrumentation;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

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
    HashSet<Thread> getEventsThreads = new HashSet<>();
    FileTransferServer fileTransferServer;


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
        Log.d("TransferService","TransferServiceBinder的构造方法被执行了");
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
    public ArrayList<ParcelableRemoteFile> listClientFiles(String path) throws RemoteException {
        Log.d("TransferService",this.toString());
        try {
            ArrayList<RemoteFile> remoteFiles = fileTransferServer.listClientFiles(path);
            ArrayList<ParcelableRemoteFile> parcelableRemoteFiles = new ArrayList<>();
            System.out.println("获取到电脑端文件,size:"+remoteFiles.size());
            for (RemoteFile remoteFile : remoteFiles) {
                System.out.println(remoteFile.getName());
                parcelableRemoteFiles.add(new ParcelableRemoteFile(remoteFile));
            }
            return parcelableRemoteFiles;
        } catch (IOException e) {
            throw new RemoteException(e.toString());
        }
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
    public List<ParcelableRemoteFile> listLocalFiles(String path) throws RemoteException{
        List<ParcelableRemoteFile> fileList = new ArrayList<>();
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null){
            return null;
        }
        for (File file : files) {
            ParcelableRemoteFile parcelableRemoteFile = new ParcelableRemoteFile(file);
            fileList.add(parcelableRemoteFile);
        }
        return fileList;
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
