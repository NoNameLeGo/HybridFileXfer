package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

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

    public int getLocalBufferCount() {
        return preferences.getInt("local_buffer_count",256);
    }

    public void setLocalBufferCount(int count){
        preferences.edit().putInt("local_buffer_count",count).apply();
    }

    public int getRemoteBufferCount() {
        return preferences.getInt("remote_buffer_count",512);
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

    /**
     * 本机稳定设备标识：握手时提供给对端作为断点续传检查点的对端键，首次使用时生成并持久化。
     * <p>不能用 IP：同一设备换连接方式（USB/WLAN）或 IP 变更后会被当成新对端，检查点全部失效。</p>
     */
    public String getDeviceId(){
        String id = preferences.getString("device_id", null);
        if (id == null || id.isEmpty()){
            id = UUID.randomUUID().toString();
            preferences.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
