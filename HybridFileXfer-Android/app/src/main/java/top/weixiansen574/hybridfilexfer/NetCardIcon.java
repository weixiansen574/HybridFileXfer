package top.weixiansen574.hybridfilexfer;

public class NetCardIcon {
    public static int matchIdForIName(String iName){
        if (iName.startsWith("wlan")) {
            return R.drawable.wifi;
        } else if (iName.equals("USB_ADB")) {
            return R.drawable.usb;
        } else if (iName.startsWith("rndis")) {
            return R.drawable.usb;
        } else if (iName.startsWith("bt")) {
            return R.drawable.bt;
        } else if (iName.startsWith("tun")) {//VPN开的虚拟网卡
            return R.drawable.ethernet;
        } else {
            return R.drawable.ethernet;
        }
    }
}
