package top.weixiansen574.hybridfilexfer;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;
import top.weixiansen574.hybridfilexfer.droidcore.Error;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableFileTransferEvent;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableTransferredBytesInfo;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ServiceConnection {
    private Context context;
    private Button btn_start_server;
    private Button btn_to_transfer;
    private Spinner spinner;
    private TextView usb_state, wifi_state;
    //private ServiceConnection connection;
    private boolean isRoot;
    private ConnectThread connectThread;
    private ITransferService iTransferService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;

        usb_state = findViewById(R.id.usb_state);
        wifi_state = findViewById(R.id.wifi_state);

        spinner = findViewById(R.id.spinner_mode);
        btn_start_server = findViewById(R.id.start_server);
        btn_to_transfer = findViewById(R.id.to_transfer);

        spinner = findViewById(R.id.spinner_mode);

        btn_start_server.setOnClickListener(this);
        btn_to_transfer.setOnClickListener(this);


    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server) {
            System.out.println(spinner.getSelectedItemPosition());
            if (!checkPermissionOrRequest()) {
                Toast.makeText(context, "需要文件读写权限", Toast.LENGTH_LONG).show();
                return;
            }
            if (iTransferService == null) {
                startServer();
            } else {
                stopServerAndDisconnectService();
            }
        } else if (id == R.id.to_transfer) {
            Intent intent = new Intent(context, TransferActivity.class);
            intent.putExtra("isRoot", isRoot);
            startActivity(intent);
        }

    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ITransferService iTransferService = ITransferService.Stub.asInterface(service);
        System.out.println("已连接service，IBinder:" + service + " iTransferService:" + iTransferService);
        if (this.iTransferService == null) {
            this.iTransferService = iTransferService;
            onConnectedService(iTransferService);
        } else {
            System.out.println("应该是sui重复启动service，不做处理");
        }

    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        System.out.println(name + "已断开连接");
    }


    private void startServer() {

        if (spinner.getSelectedItemPosition() == 1) {
            System.out.println("以Root用户启动服务");
            if (!Sui.init(getPackageName())){
                Toast.makeText(context, "未安装Sui模块，若你已有Magisk的root，还需要刷入这个模块", Toast.LENGTH_SHORT).show();
                return;
            } else {
                if(Shizuku.checkSelfPermission() != 0){
                    Toast.makeText(context, "未授权，请点击弹窗的允许或自行到Sui管理面板允许", Toast.LENGTH_SHORT).show();
                    Shizuku.requestPermission(1);
                    return;
                }
            }
            isRoot = true;
        } else {
            System.out.println("以普通用户启动服务");
            isRoot = false;
        }

        btn_start_server.setEnabled(false);
        bindService();
    }


    private void bindService() {
        if (isRoot) {
            Shizuku.bindUserService(Utils.getUserServiceArgs(context), this);
        } else {
            Intent intent = new Intent(context, TransferServices.class);
            bindService(intent, this, Service.BIND_AUTO_CREATE);
        }
    }

    private void unbindService() {
        if (isRoot) {
            Shizuku.unbindUserService(Utils.getUserServiceArgs(context), this, true);
        } else {
            unbindService(this);
        }
    }

    private void onConnectedService(ITransferService service) {
        btn_start_server.setEnabled(true);
        usb_state.setText("等待电脑连接");
        wifi_state.setText("等待电脑连接");
        btn_start_server.setText("停止服务器");
        connectThread = new ConnectThread(this, service);
        connectThread.start();
    }

    public void stopServerAndDisconnectService() {
        btn_start_server.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("正在等待服务端停止");
                    iTransferService.stopServer();
                    System.out.println("服务端已停止");

                    if (connectThread != null) {
                        connectThread.shutdown();
                        try {
                            connectThread.join();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    runOnUiThread(() -> {
                        unbindService();
                        onServerStopped();
                    });
                } catch (RemoteException e) {
                    runOnUiThread(() -> {
                        unbindService();
                        onServerStopped();
                    });
                }

            }
        }).start();
    }

    private void onServerStopped(){
        iTransferService = null;
        usb_state.setText("未运行");
        wifi_state.setText("未运行");
        btn_start_server.setText("启动服务器并等待连接");
        btn_start_server.setEnabled(true);
        btn_to_transfer.setEnabled(false);
    }

    private void onServerStarted() {
        usb_state.setText("已连接");
        wifi_state.setText("已连接");
        btn_to_transfer.setEnabled(true);

    }

    private boolean checkPermissionOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 检查是否有所有文件访问权限
            if (Environment.isExternalStorageManager()) {
                // 已经获得文件访问权限
                return true;
            } else {
                // 请求文件访问权限
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, 0);
                return false;
            }
        } else {
            // 检查是否有存储权限
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // 已经获得存储权限
                return true;
            } else {
                // 请求存储权限
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
                return false;
            }
        }
    }

    public static class ConnectThread extends Thread {
        private final MainActivity activity;
        private boolean shutdown = false;
        private final ITransferService service;

        public ConnectThread(MainActivity activity, ITransferService service) {
            this.activity = activity;
            this.service = service;
        }

        @Override
        public void run() {
            try {
                Error error = service.startServer();
                activity.runOnUiThread(() -> {
                    if (error != null) {
                        //shutdown的判断应该放在UI线程内
                        if (!shutdown) {
                            Toast.makeText(activity, error.getErrorCode() == Error.CODE_PORT_IS_OCCUPIED ?
                                            "端口被占用，启动失败，请停止占用5740,5741,5742端口的程序！" :
                                            "服务端启动失败，因为：" + error.getExceptionMessage()
                                    , Toast.LENGTH_SHORT).show();
                            activity.stopServerAndDisconnectService();
                        } else {
                            System.out.println("已shutdown，不执行解绑service");
                        }
                    } else {
                        activity.onServerStarted();
                    }
                });
                //如果服务器等待至被连接时无错误，则进行等待直到正常退出或异常退出
                if (error == null) {
                    service.waitingForDied();
                    System.out.println("服务已正常退出");
                }
            } catch (RemoteException e) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "服务异常退出！", Toast.LENGTH_SHORT).show();
                    activity.stopServerAndDisconnectService();
                });
            }
        }

        public void shutdown() {
            shutdown = true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (iTransferService != null) {
            System.out.println("正在销毁服务……");
            unbindService();
        }
    }
}