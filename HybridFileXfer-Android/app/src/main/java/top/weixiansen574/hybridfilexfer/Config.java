package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.content.SharedPreferences;

public class Config {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_ROOT = 1;
    private static Config instance;
    SharedPreferences preferences;

    public Config(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public static synchronized Config getInstance(Context context) {
        if (instance == null){
            instance = new Config(context.getApplicationContext().getSharedPreferences("config",Context.MODE_PRIVATE));
        }
        return instance;
    }

    public int getMode(){
        return preferences.getInt("mode",MODE_NORMAL);
    }

    public void setMode(int mode){
        preferences.edit().putInt("mode",mode).apply();
    }

    public int getServerPort() {
        return 5740;
    }

    /**
     * 缓冲区块数。块大小已从 1MB 提升到 2MB，这里同步减半，
     * 保证总内存预算不变（128 x 2MB = 256MB）。
     */
    public int getLocalBufferCount() {
        return preferences.getInt("local_buffer_count",128);
    }

    public void setLocalBufferCount(int count){
        preferences.edit().putInt("local_buffer_count",count).apply();
    }

    /** 同上：256 x 2MB = 512MB，与改造前的 512 x 1MB 等价 */
    public int getRemoteBufferCount() {
        return preferences.getInt("remote_buffer_count",256);
    }

    public void setRemoteBufferCount(int count){
        preferences.edit().putInt("remote_buffer_count",count).apply();
    }

    public void setClientIOMode(int mode){
        preferences.edit().putInt("client_io_mode",mode).apply();
    }

    public int getClientIOMode(){
        return preferences.getInt("client_io_mode", MODE_NORMAL);
    }

    public void setConnectServerControllerIp(String ip){
        preferences.edit().putString("connect_server_controller_ip",ip).apply();
    }

    public String getConnectServerControllerIp(){
        return preferences.getString("connect_server_controller_ip","");
    }
}
