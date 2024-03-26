package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import top.weixiansen574.hybridfilexfer.core.threads.ClientControllerThread;
import top.weixiansen574.hybridfilexfer.core.threads.ReceiveThread;

public class FileTransferClient implements ServerInfo{
    ClientControllerThread controllerThread;
    public void startUp(){
        controllerThread = new ClientControllerThread();
        controllerThread.start();
    }


}
