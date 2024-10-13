// IHFXService.aidl
package top.weixiansen574.hybridfilexfer;

// Declare any non-default types here with import statements
import top.weixiansen574.hybridfilexfer.core.bean.SocketConnectStatus;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

interface IHFXService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server
    //void exit() = 1; // Exit method defined by user
    boolean bind(int port) = 2;
    void closeServer() = 3;


    int[] listLocalFiles(String path) = 10;
    List<RemoteFile> getAndRemoveLocalFileListSlice(int sliceId) = 11;

    boolean writeListFiles(String path) = 20;
    int[] listClientFiles() = 21;
    List<RemoteFile> getAndRemoveRemoteFileListSlice(int sliceId) = 22;

    TransferEvent getCurrentTransferEvent() = 30;
    List<TrafficInfo> getTrafficInfoList() = 31;

    boolean deleteLocalFile(String file) = 40;
    boolean deleteRemoteFile(String file) = 41;

    boolean createLocalDir(String parent,String child) = 43;
    boolean createRemoteDir(String parent,String child) = 44;

    boolean waitToConnect(in List<ServerNetInterface> interfaceList) = 100;
    SocketConnectStatus acceptTransferChannel() = 110;
    List<String> getChannelListINames() = 120;

    boolean requestRemoteReceive() = 200;//请求对方进入接收，发送文件前调用
    String sendFilesToRemote(in List<String> files, String localDir, String remoteDir) = 210;

    boolean requestRemoteSend(in List<String> files, String localDir, String remoteDir) = 300;
    String receiveFiles() = 310;

    void test() = 0;


}