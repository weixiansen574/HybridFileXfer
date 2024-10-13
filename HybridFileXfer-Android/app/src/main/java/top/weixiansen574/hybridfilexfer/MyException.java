package top.weixiansen574.hybridfilexfer;

import android.os.Parcel;
import android.os.Parcelable;


public class MyException extends RuntimeException implements Parcelable {
    String msg;

    public MyException(String message) {
        super(message);
        this.msg = message;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.msg);
    }

    public MyException() {
    }

    protected MyException(Parcel in) {
        this.msg = in.readString();
    }

    public static final Parcelable.Creator<MyException> CREATOR = new Parcelable.Creator<MyException>() {
        @Override
        public MyException createFromParcel(Parcel source) {
            return new MyException(source);
        }

        @Override
        public MyException[] newArray(int size) {
            return new MyException[size];
        }
    };
}
