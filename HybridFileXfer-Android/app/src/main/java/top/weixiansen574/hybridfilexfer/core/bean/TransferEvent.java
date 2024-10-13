package top.weixiansen574.hybridfilexfer.core.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class TransferEvent implements Parcelable {
    public static final int TYPE_ERROR = -1;//传输通道断开
    public static final int TYPE_INTERRUPTED = -2;//因某一传输通道断开，此通道也被迫中断
    public static final int TYPE_DOWNLOAD = 1;
    public static final int TYPE_UPLOAD = 2;
    public static final int TYPE_DOWNLOAD_OVER = 11;
    public static final int TYPE_UPLOAD_OVER = 12;
    public int type;
    public String iName;
    public String content;

    public TransferEvent(int type, String iName, String content) {
        this.type = type;
        this.iName = iName;
        this.content = content;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.type);
        dest.writeString(this.iName);
        dest.writeString(this.content);
    }

    public TransferEvent() {
    }

    protected TransferEvent(Parcel in) {
        this.type = in.readInt();
        this.iName = in.readString();
        this.content = in.readString();
    }

    public static final Parcelable.Creator<TransferEvent> CREATOR = new Parcelable.Creator<TransferEvent>() {
        @Override
        public TransferEvent createFromParcel(Parcel source) {
            return new TransferEvent(source);
        }

        @Override
        public TransferEvent[] newArray(int size) {
            return new TransferEvent[size];
        }
    };
}
