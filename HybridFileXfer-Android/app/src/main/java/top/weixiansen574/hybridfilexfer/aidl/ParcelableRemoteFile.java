package top.weixiansen574.hybridfilexfer.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class ParcelableRemoteFile extends RemoteFile implements Parcelable {
    protected ParcelableRemoteFile(Parcel in) {
        super(in.readString(), in.readString(), in.readLong(), in.readLong(), in.readByte() != 0);
    }

    public ParcelableRemoteFile(File file) {
        super(file);
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
