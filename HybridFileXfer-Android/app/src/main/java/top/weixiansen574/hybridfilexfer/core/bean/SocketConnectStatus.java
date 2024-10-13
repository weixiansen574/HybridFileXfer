package top.weixiansen574.hybridfilexfer.core.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class SocketConnectStatus implements Parcelable {
    public String name;
    public boolean success;

    public SocketConnectStatus() {
    }

    public SocketConnectStatus(String name, boolean success) {
        this.name = name;
        this.success = success;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeByte(this.success ? (byte) 1 : (byte) 0);
    }

    public void readFromParcel(Parcel source) {
        this.name = source.readString();
        this.success = source.readByte() != 0;
    }

    protected SocketConnectStatus(Parcel in) {
        this.name = in.readString();
        this.success = in.readByte() != 0;
    }

    public static final Parcelable.Creator<SocketConnectStatus> CREATOR = new Parcelable.Creator<SocketConnectStatus>() {
        @Override
        public SocketConnectStatus createFromParcel(Parcel source) {
            return new SocketConnectStatus(source);
        }

        @Override
        public SocketConnectStatus[] newArray(int size) {
            return new SocketConnectStatus[size];
        }
    };
}
