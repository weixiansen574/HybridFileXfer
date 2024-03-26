// ITransferService.aidl
package top.weixiansen574.hybridfilexfer;

// Declare any non-default types here with import statements

interface ITransferService {
    void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1; // Exit method defined by user

    top.weixiansen574.hybridfilexfer.droidcore.Error startServer() = 2;
    void stopServer() = 3;
    void waitingForDied() = 4;
    void stopGetNextEvent() = 5;

    void transferToPc(in List<String> files,String localDir,String remoteDir) = 101;
    void transferToMe(in List<String> files, String remoteDir, String localDir) = 102;
    List<top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile> listClientFiles(String path) = 100;
    top.weixiansen574.hybridfilexfer.droidcore.ParcelableFileTransferEvent getNextFileTransferEvent() = 110;
    top.weixiansen574.hybridfilexfer.droidcore.ParcelableTransferredBytesInfo getTransferredBytesInfo() = 111;

    List<top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile> listLocalFiles(String path) = 200;
    top.weixiansen574.hybridfilexfer.droidcore.ParcelableRemoteFile getParentFile(String path) = 201;




}