package top.weixiansen574.hybridfilexfer.core.threads;

import java.util.concurrent.BlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.DevicesInterface;
import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;

public abstract class TransferThread extends Thread implements DevicesInterface {
    protected long transferredBytes;
    protected final BlockingDeque<FileTransferEvent> events;
    public final int device;
    protected OnExceptionListener onExceptionListener;

    public TransferThread(BlockingDeque<FileTransferEvent> events, int device) {
        this.events = events;
        this.device = device;
    }

    public long getAndResetTransferredBytes(){
        long bytes = transferredBytes;
        transferredBytes = 0;
        return bytes;
    }

    protected void addEvent(int state, String desc){
        events.add(new FileTransferEvent(state,device,desc));
    }

    public void setOnExceptionListener(OnExceptionListener onExceptionListener) {
        this.onExceptionListener = onExceptionListener;
    }

    public interface OnExceptionListener{
        void onException(Exception e);
    }
}
