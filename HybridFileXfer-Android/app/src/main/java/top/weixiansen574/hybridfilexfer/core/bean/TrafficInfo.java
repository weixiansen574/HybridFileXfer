package top.weixiansen574.hybridfilexfer.core.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class TrafficInfo implements Parcelable {
    public String iName;
    public long uploadTraffic;
    public long downloadTraffic;

    public TrafficInfo(String iName, long uploadTraffic, long downloadTraffic) {
        this.iName = iName;
        this.uploadTraffic = uploadTraffic;
        this.downloadTraffic = downloadTraffic;
    }
    public TrafficInfo() {
    }
    public TrafficInfo(String iName) {
        this.iName = iName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.iName);
        dest.writeLong(this.uploadTraffic);
        dest.writeLong(this.downloadTraffic);
    }

    public void readFromParcel(Parcel source) {
        this.iName = source.readString();
        this.uploadTraffic = source.readLong();
        this.downloadTraffic = source.readLong();
    }

    protected TrafficInfo(Parcel in) {
        this.iName = in.readString();
        this.uploadTraffic = in.readLong();
        this.downloadTraffic = in.readLong();
    }

    public static final Parcelable.Creator<TrafficInfo> CREATOR = new Parcelable.Creator<TrafficInfo>() {
        @Override
        public TrafficInfo createFromParcel(Parcel source) {
            return new TrafficInfo(source);
        }

        @Override
        public TrafficInfo[] newArray(int size) {
            return new TrafficInfo[size];
        }
    };
}
