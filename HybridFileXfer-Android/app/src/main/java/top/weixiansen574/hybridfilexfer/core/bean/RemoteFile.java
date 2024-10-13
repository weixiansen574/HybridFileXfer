package top.weixiansen574.hybridfilexfer.core.bean;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.io.File;

public class RemoteFile implements Parcelable {
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

    protected RemoteFile(Parcel in) {
        this.name = in.readString();
        this.path = in.readString();
        this.lastModified = in.readLong();
        this.size = in.readLong();
        this.isDirectory = in.readByte() != 0;
    }

    protected RemoteFile(){}

    @NonNull
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeString(this.path);
        dest.writeLong(this.lastModified);
        dest.writeLong(this.size);
        dest.writeByte(this.isDirectory ? (byte) 1 : (byte) 0);
    }

    public void readFromParcel(Parcel source) {
        this.name = source.readString();
        this.path = source.readString();
        this.lastModified = source.readLong();
        this.size = source.readLong();
        this.isDirectory = source.readByte() != 0;
    }



    public static final Creator<RemoteFile> CREATOR = new Creator<RemoteFile>() {
        @Override
        public RemoteFile createFromParcel(Parcel source) {
            return new RemoteFile(source);
        }

        @Override
        public RemoteFile[] newArray(int size) {
            return new RemoteFile[size];
        }
    };
}
