// IIOService.aidl
package top.weixiansen574.hybridfilexfer.aidl;

import top.weixiansen574.hybridfilexfer.aidl.ParcelableRemoteFile;

interface IIOService {
    ParcelFileDescriptor createAndOpenWriteableFile(String path, long length) = 1;
    String createParentDirIfNotExists(String path) = 12;
    boolean setFileLastModified(String path, long time) = 5;
    ParcelFileDescriptor openReadableFile(String path) = 2;
    String tryMkdirs(String path) = 13;
    int[] listFiles(String path) = 3;
    List<ParcelableRemoteFile> getAndRemoveFileListSlice(int sliceId) = 4;
    boolean deleteFile(String path) = 6;
    boolean fileExists(String path) = 7;
    boolean appendAndMkdirs(String parent, String child) = 8;
    boolean mkdirs(String path) = 9;
    boolean isFile(String path) = 10;
    String getFileParent(String path) = 11;
    String[] describeTransferFile(String path) = 20;
    ParcelFileDescriptor openTransferSource(String path) = 21;
    String[] prepareTransferFile(String root, String relative, String identity, long size, long modified,
        String hash, int policy, boolean resume, boolean incremental) = 22;
    ParcelFileDescriptor openTransferStage(String token, String handleId) = 23;
    String checkpointTransferFile(String token, long offset) = 24;
    String commitTransferFile(String token, boolean verifySha256) = 25;
    String abandonTransferFile(String token, boolean keep) = 26;
    String createTransferDirectory(String root, String relative, long modified) = 27;
    String[] canonicalTransferPath(String path) = 28;
    void beginTransferFileSession() = 29;
    void cancelPendingTransferOperations() = 30;
    String[] takeTransferFileWarnings() = 31;
    String sealTransferFile(String token) = 32;
    ParcelFileDescriptor openSealedTransferFile(String token, String handleId) = 33;
    String completeTransferVerification(String token, String expected, String actual) = 34;
    String releaseTransferVerification(String token) = 35;
    String resetTransferFileForRetry(String token) = 36;
    String[] publishUnverifiedTransferFile(String token) = 37;
    String retainUnverifiedTransferFile(String token) = 38;
    String endTransferFileSession() = 39;
    String closeTransferHandle(String token, String handleId) = 40;
    void destroy() = 16777114;
}
