package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;

public class ParcelableFileTransferEvent extends FileTransferEvent implements Parcelable {
    public ParcelableFileTransferEvent(FileTransferEvent event) {
        super(event.getState(), event.getDevice(), event.getDesc());
    }
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.state);
        dest.writeInt(this.device);
        dest.writeString(this.desc);
    }

    public void readFromParcel(Parcel source) {
        this.state = source.readInt();
        this.device = source.readInt();
        this.desc = source.readString();
    }

    protected ParcelableFileTransferEvent(Parcel in) {
        super(in.readInt(),in.readInt(),in.readString());
    }

    public static final Creator<ParcelableFileTransferEvent> CREATOR = new Creator<ParcelableFileTransferEvent>() {
        @Override
        public ParcelableFileTransferEvent createFromParcel(Parcel source) {
            return new ParcelableFileTransferEvent(source);
        }

        @Override
        public ParcelableFileTransferEvent[] newArray(int size) {
            return new ParcelableFileTransferEvent[size];
        }
    };
}
