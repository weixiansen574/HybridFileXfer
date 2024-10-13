package top.weixiansen574.hybridfilexfer.core.bean;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.net.InetAddress;

public class ServerNetInterface implements Parcelable {
    public String name;
    public InetAddress address;
    public InetAddress clientBindAddress;

    public ServerNetInterface(String name, InetAddress address, InetAddress clientBindAddress) {
        this.name = name;
        this.address = address;
        this.clientBindAddress = clientBindAddress;
    }


    @NonNull
    @Override
    public String toString() {
        return "ServerNetInterface{" +
                "name='" + name + '\'' +
                ", address=" + address +
                ", clientBindAddress=" + clientBindAddress +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeSerializable(this.address);
        dest.writeSerializable(this.clientBindAddress);
    }

    public void readFromParcel(Parcel source) {
        this.name = source.readString();
        this.address = (InetAddress) source.readSerializable();
        this.clientBindAddress = (InetAddress) source.readSerializable();
    }

    public ServerNetInterface(Parcel in) {
        this.name = in.readString();
        this.address = (InetAddress) in.readSerializable();
        this.clientBindAddress = (InetAddress) in.readSerializable();
    }

    public static final Parcelable.Creator<ServerNetInterface> CREATOR = new Parcelable.Creator<ServerNetInterface>() {
        @Override
        public ServerNetInterface createFromParcel(Parcel source) {
            return new ServerNetInterface(source);
        }

        @Override
        public ServerNetInterface[] newArray(int size) {
            return new ServerNetInterface[size];
        }
    };
}
