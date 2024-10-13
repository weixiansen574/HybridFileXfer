package top.weixiansen574.hybirdfilexfer.core.bean;

import top.weixiansen574.hybirdfilexfer.core.Utils;

import java.io.File;

public class FileTransferJob {

    public final File localDir;
    public final String remoteDir;
    public final File targetFile;
    public final boolean isSlice;
    public final long startRange;
    public final long endRange;

    public FileTransferJob(File localDir, String remoteDir, File target) {
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.targetFile = target;
        this.isSlice = false;
        this.startRange = 0;
        this.endRange = 0;
    }

    public FileTransferJob(File localDir, String remoteDir, File target, long startRange, long endRange) {
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.targetFile = target;
        this.startRange = startRange;
        this.endRange = endRange;
        this.isSlice = true;
    }

    public FileTransferJob(FileTransferJob oldJob, long startRange, long endRange) {
        this.localDir = oldJob.localDir;
        this.remoteDir = oldJob.remoteDir;
        this.targetFile = oldJob.targetFile;
        this.startRange = startRange;
        this.endRange = endRange;
        this.isSlice = true;
    }

    public String toRemotePath() {
        // /sdcard/ > [/sdcard/test/] --> E:\\transfer\\ ==> E:/transfer/test/
        // E:\\transfer\\ > [E:\\transfer\\test\\] --> /sdcard/ ==> /sdcard/test/

        // 检查输入是否为空
        if (localDir == null || targetFile == null || remoteDir == null) {
            throw new IllegalArgumentException("localDir, targetFile, and remoteDir must not be null.");
        }

        // 获取rootDir和targetFile的路径
        String localDirPath = localDir.getAbsolutePath();
        String targetPath = targetFile.getAbsolutePath();

        //统一用"/"，若对方是windows，"\" -> "/" ，若对方是Linux，不会改变啥
        String remotePath = Utils.replaceBackslashToSlash(remoteDir);
        // 确保rootPath以文件分隔符结尾，以便连接目标文件的相对路径
        if (!localDirPath.endsWith(File.separator)) {
            localDirPath += File.separator;
        }

        // 计算targetFile相对于rootDir的路径
        String relativePath = targetPath.startsWith(localDirPath) ? targetPath.substring(localDirPath.length()) : targetPath;

        //替换“非法”字符，并统一使用"/"做分割符
        relativePath = processFileNamesAccordingToTheSystem(relativePath);
        // 拼接远程路径
        return remotePath + (remotePath.endsWith("/") ? "" : "/") + relativePath;
    }

    public String processFileNamesAccordingToTheSystem(String path){
        //判断是否为Linux（安卓）系统
        if (File.separator.equals("/")){
            //为传输到windows不出错，和谐掉Linux文件名能有但windows不能有的":"和"\"
            path = Utils.replaceColon(path);
            path = Utils.replaceBackslashToUnderline(path);
        } else {
            //为传输到Linux不产生带"\"文件名导致出错（或者出现带"\"文件名的掉san玩意）
            path = Utils.replaceBackslashToSlash(path);
        }
        return path;
    }
    public long getTotalSize(){
        return targetFile.length();
    }

    @Override
    public String toString() {
        return "FileTransferJob{" +
                "localDir=" + localDir +
                ", remoteDir='" + remoteDir + '\'' +
                ", targetFile=" + targetFile +
                ", isSlice=" + isSlice +
                ", startRange=" + startRange +
                ", endRange=" + endRange +
                '}';
    }
}
