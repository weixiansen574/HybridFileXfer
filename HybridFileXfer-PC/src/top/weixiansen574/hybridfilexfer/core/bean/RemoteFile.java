package top.weixiansen574.hybridfilexfer.core.bean;

import java.io.File;
import java.io.Serializable;

public class RemoteFile implements Serializable {
    protected String name;
    protected String path;
    protected long lastModified;
    protected long size;
    protected boolean isDirectory;

    public RemoteFile(File file) {
        path = file.getPath();
        isDirectory = file.isDirectory();
        name = file.getName();
        lastModified = file.lastModified();
        size = file.length();
    }

    public RemoteFile(String name, String path, long lastModified, long size, boolean isDirectory){
        this.name = name;
        this.path = path;
        this.lastModified = lastModified;
        this.size = size;
        this.isDirectory = isDirectory;
    }

    public RemoteFile(RemoteFile file){
        this.name = file.name;
        this.path = file.path;
        this.lastModified = file.lastModified;
        this.size = file.size;
        this.isDirectory = file.isDirectory;
    }

    protected RemoteFile(){}

    @Override
    public String toString() {
        return "RemoteFile{" +
                "path='" + path + '\'' +
                ", isDirectory=" + isDirectory +
                '}';
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getName() {
        return name;
    }

    public long getLastModified() {
        return lastModified;
    }

    public long getSize() {
        return size;
    }
}
