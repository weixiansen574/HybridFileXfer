package top.weixiansen574.hybridfilexfer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class Utils {
    public static final String ILLEGAL_CHARACTERS = "<>:\"/\\|?*";
    public static void sortFiles(List<RemoteFile> fileList) {
        // 使用Comparator自定义比较规则
        fileList.sort((f1, f2) -> {
            // 文件夹优先于文件
            if (f1.isDirectory() && !f2.isDirectory()) {
                return -1;
            } else if (!f1.isDirectory() && f2.isDirectory()) {
                return 1;
            } else {
                // 如果类型相同，则按名称排序
                return f1.getName().compareTo(f2.getName());
            }
        });
    }

    //因为一个奇怪的问题：如果File对象是一些特殊的路径
    //例如：/data/，那么getParent()方法返回的是null

    public static String getParentByPath(String path) {
        //如果末尾没有路径分隔符，则添加路径分隔符
        if (!path.endsWith("/")){
            path += "/";
        }
        int lastIndex = path.lastIndexOf("/");
        if (lastIndex != -1) {
            int secondLastIndex = path.lastIndexOf("/", lastIndex - 1);
            if (secondLastIndex != -1) {
                return path.substring(0, secondLastIndex) + "/";
            } else if (lastIndex != 0){//windows盘符路径特殊适配，如"C:/"返回"/"
                return "/";
            }
        }
        return null; // No parent path found
    }

    public static String formatSpeed(long bytesPerSecond){
        if (bytesPerSecond < 1024){
            return bytesPerSecond + "B/s";
        }
        if (bytesPerSecond < 1024 * 1024){
            return String.format(Locale.getDefault(),"%.2fKB/s", bytesPerSecond / 1024.0);
        }
        return String.format(Locale.getDefault(),"%.2fMB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    public static String formatDateTime(long milliseconds){
        //格式化为年-月-日 时:分
        return new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()).format(new Date(milliseconds));
    }

    public static String formatFileSize(long size){
        if (size < 1024){
            return size + "B";
        }
        if (size < 1024 * 1024){
            return String.format(Locale.getDefault(),"%.2fKB", size / 1024.0);
        }
        if (size < 1024 * 1024 * 1024){
            return String.format(Locale.getDefault(),"%.2fMB", size / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(),"%.2fGB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public static boolean containsIllegalCharacters(String text) {
        for (char c : ILLEGAL_CHARACTERS.toCharArray()) {
            if (text.indexOf(c) >= 0) {
                return true; // 如果找到非法字符，返回 true
            }
        }
        return false; // 如果没有找到非法字符，返回 false
    }

    public static int matchIconIdForIName(String iName){
        if (iName.startsWith("wlan")) {
            return R.drawable.wifi;
        } else if (iName.equals("USB_ADB")) {
            return R.drawable.usb;
        } else if (iName.startsWith("rndis")){
            return R.drawable.usb;
        } else if (iName.startsWith("bt")){
            return R.drawable.bt;
        } else if (iName.startsWith("tun")) {//VPN开的虚拟网卡
            return R.drawable.ethernet;
        } else {
            return R.drawable.ethernet;
        }
    }

}
