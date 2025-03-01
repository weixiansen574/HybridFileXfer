// IIOService.aidl
package top.weixiansen574.hybridfilexfer.aidl;

import top.weixiansen574.hybridfilexfer.aidl.ParcelableRemoteFile;

interface IIOService {
    void destroy() = 16777114;
    ParcelFileDescriptor createAndOpenWriteableFile(String path, long length) = 1;
    ParcelFileDescriptor openReadableFile(String path) = 2;
    int[] listFiles(String path) = 3;
    List<ParcelableRemoteFile> getAndRemoveFileListSlice(int sliceId) = 4;
    boolean setFileLastModified(String path, long time) = 5;
    boolean deleteFile(String path) = 6;
    boolean fileExists(String path) = 7;
    boolean appendAndMkdirs(String parent, String child) = 8;
    boolean mkdirs(String path) = 9;
    boolean isFile(String path) = 10;
    String getFileParent(String path) = 11;
    String createParentDirIfNotExists(String path) = 12;
    String tryMkdirs(String path) = 13;
}
