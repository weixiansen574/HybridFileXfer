package top.weixiansen574.hybridfilexfer.core.bean;

public class FileTransferEvent implements DevicesInterface {
    public static final int STATE_DOWNLOAD = 1;
    public static final int STATE_UPLOAD = 2;
    public static final int STATE_OVER = 0;
    protected int state;
    protected int device;
    protected String desc;
    public FileTransferEvent(int state, int device, String desc) {
        this.state = state;
        this.device = device;
        this.desc = desc;
    }

    public int getState() {
        return state;
    }

    public int getDevice() {
        return device;
    }

    public String getDesc() {
        return desc;
    }
}
