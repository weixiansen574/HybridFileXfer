package top.weixiansen574.hybridfilexfer.async;

import android.os.RemoteException;

import top.weixiansen574.async.EventHandler;

public abstract class IIServiceRequestHandler extends EventHandler {
    ErrorHandler errorHandler;

    public IIServiceRequestHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    public void setErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    protected void handleError(Throwable th) {
        th.printStackTrace();
        if (th instanceof RemoteException){
            errorHandler.handleRemoteException((RemoteException) th);
        }else {
            errorHandler.handleOtherExceptions(th);
        }
    }

    public interface ErrorHandler {
        void handleRemoteException(RemoteException e);
        void handleOtherExceptions(Throwable th);
    }
}
