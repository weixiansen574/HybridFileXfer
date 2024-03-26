package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.Parcel;
import android.os.Parcelable;

import top.weixiansen574.hybridfilexfer.core.bean.TransferredBytesInfo;

public class ParcelableTransferredBytesInfo extends TransferredBytesInfo implements Parcelable {
    public ParcelableTransferredBytesInfo(TransferredBytesInfo info) {
        super(info.getUsbReceiveBytes(), info.getUsbSentBytes(), info.getWifiReceiveBytes(), info.getWifiSentBytes());
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(this.usbReceiveBytes);
        dest.writeLong(this.usbSentBytes);
        dest.writeLong(this.wifiReceiveBytes);
        dest.writeLong(this.wifiSentBytes);
    }

    public void readFromParcel(Parcel source) {
        this.usbReceiveBytes = source.readLong();
        this.usbSentBytes = source.readLong();
        this.wifiReceiveBytes = source.readLong();
        this.wifiSentBytes = source.readLong();
    }

    protected ParcelableTransferredBytesInfo(Parcel in) {
        super(in.readLong(),in.readLong(),in.readLong(),in.readLong());
    }

    public static final Creator<ParcelableTransferredBytesInfo> CREATOR = new Creator<ParcelableTransferredBytesInfo>() {
        @Override
        public ParcelableTransferredBytesInfo createFromParcel(Parcel source) {
            return new ParcelableTransferredBytesInfo(source);
        }

        @Override
        public ParcelableTransferredBytesInfo[] newArray(int size) {
            return new ParcelableTransferredBytesInfo[size];
        }
    };
}
