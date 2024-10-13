package top.weixiansen574.hybridfilexfer;

import android.os.Parcel;
import android.os.Parcelable;

public class MyCustomException extends RuntimeException implements Parcelable {
    private String errorMessage;

    public MyCustomException(String message) {
        super(message);
        this.errorMessage = message;
    }

    // Parcelable implementation
    protected MyCustomException(Parcel in) {
        errorMessage = in.readString();
    }

    public static final Creator<MyCustomException> CREATOR = new Creator<MyCustomException>() {
        @Override
        public MyCustomException createFromParcel(Parcel in) {
            return new MyCustomException(in);
        }

        @Override
        public MyCustomException[] newArray(int size) {
            return new MyCustomException[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(errorMessage);
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
