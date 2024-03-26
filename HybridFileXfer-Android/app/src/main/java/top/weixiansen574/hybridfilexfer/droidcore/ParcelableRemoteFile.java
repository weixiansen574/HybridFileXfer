package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class ParcelableRemoteFile extends RemoteFile implements Parcelable{



    public ParcelableRemoteFile(File file) {
        super(file);
    }

    public ParcelableRemoteFile(RemoteFile remoteFile){
        super(remoteFile);
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

    protected ParcelableRemoteFile(Parcel in) {
        this.name = in.readString();
        this.path = in.readString();
        this.lastModified = in.readLong();
        this.size = in.readLong();
        this.isDirectory = in.readByte() != 0;
    }

    public static final Creator<ParcelableRemoteFile> CREATOR = new Creator<ParcelableRemoteFile>() {
        @Override
        public ParcelableRemoteFile createFromParcel(Parcel source) {
            return new ParcelableRemoteFile(source);
        }

        @Override
        public ParcelableRemoteFile[] newArray(int size) {
            return new ParcelableRemoteFile[size];
        }
    };
}
