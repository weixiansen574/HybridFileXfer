package top.weixiansen574.hybridfilexfer.core.bean;

public class TransferredBytesInfo {
    protected long usbReceiveBytes;
    protected long usbSentBytes;
    protected long wifiReceiveBytes;
    protected long wifiSentBytes;

    public TransferredBytesInfo(long usbReceiveBytes, long usbSentBytes, long wifiReceiveBytes, long wifiSentBytes) {
        this.usbReceiveBytes = usbReceiveBytes;
        this.usbSentBytes = usbSentBytes;
        this.wifiReceiveBytes = wifiReceiveBytes;
        this.wifiSentBytes = wifiSentBytes;
    }

    public long getUsbReceiveBytes() {
        return usbReceiveBytes;
    }

    public long getUsbSentBytes() {
        return usbSentBytes;
    }

    public long getWifiReceiveBytes() {
        return wifiReceiveBytes;
    }

    public long getWifiSentBytes() {
        return wifiSentBytes;
    }

}
