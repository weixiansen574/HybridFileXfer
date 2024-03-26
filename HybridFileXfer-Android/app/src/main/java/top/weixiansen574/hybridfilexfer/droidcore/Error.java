package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.Parcel;
import android.os.Parcelable;

public class Error implements Parcelable {
    public static final int CODE_IOEXCEPTION = 0;
    public static final int CODE_PORT_IS_OCCUPIED = 1;


    protected int errorCode;
    protected String exceptionMessage;

    public Error(int errorCode, String exceptionMessage) {
        this.errorCode = errorCode;
        this.exceptionMessage = exceptionMessage;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.errorCode);
        dest.writeString(this.exceptionMessage);
    }

    public void readFromParcel(Parcel source) {
        this.errorCode = source.readInt();
        this.exceptionMessage = source.readString();
    }

    protected Error(Parcel in) {
        this.errorCode = in.readInt();
        this.exceptionMessage = in.readString();
    }

    public static final Parcelable.Creator<Error> CREATOR = new Parcelable.Creator<Error>() {
        @Override
        public Error createFromParcel(Parcel source) {
            return new Error(source);
        }

        @Override
        public Error[] newArray(int size) {
            return new Error[size];
        }
    };

    public int getErrorCode() {
        return errorCode;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }
}
