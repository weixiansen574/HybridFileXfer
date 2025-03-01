package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;
import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidserver.HFXServer;
import top.weixiansen574.hybridfilexfer.droidserver.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.listadapter.NetCardsAdapter;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ServiceConnection {
    public static final int REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = 1;
    public static final int REQUEST_CODE_TRANSFER = 2;
    public static final int RESULT_CODE_SERVER_DISCONNECT = 1;
    private NetCardsAdapter netCardsAdapter;
    private Spinner spinnerMode;
    Button startServerBtn;
    Button toTransfer;
    Context context;
    private boolean isRoot = false;
    //private boolean state = true;
    private HFXServer server;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        config = Config.getInstance(context);
        startServerBtn = findViewById(R.id.start_server);
        startServerBtn.setOnClickListener(this);
        toTransfer = findViewById(R.id.to_transfer);
        toTransfer.setOnClickListener(this);
        spinnerMode = findViewById(R.id.spinner_mode);
        findViewById(R.id.refresh).setOnClickListener(this);

        RecyclerView recyclerView = findViewById(R.id.rec_view_net_cards);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        try {
            netCardsAdapter = new NetCardsAdapter(this);
            recyclerView.setAdapter(netCardsAdapter);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            startServerBtn.setEnabled(false);
        }

        spinnerMode.setSelection(config.getMode());

        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                config.setMode(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server) {
            if (server == null) {
                startServer();
            } else {
                disconnect(true);
            }
        } else if (id == R.id.to_transfer) {
            startActivityForResult(new Intent(context, TransferActivity.class), REQUEST_CODE_TRANSFER);
        } else if (id == R.id.refresh) {
            if (server == null) {
                try {
                    netCardsAdapter.reload();
                } catch (SocketException | UnknownHostException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, R.string.qing_xian_ting_zhi_fu_wu_duan, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void startServer() {
        if (!checkPermissionOrRequest()) {
            Toast.makeText(context, R.string.xu_yao_wen_jian_du_xie_quan_xian, Toast.LENGTH_LONG).show();
            return;
        }
        List<ServerNetInterface> selectedInterfaces = netCardsAdapter.getSelectedInterfaces();
        if (selectedInterfaces == null) {
            return;
        }
        if (selectedInterfaces.isEmpty()) {
            Toast.makeText(context, R.string.mei_you_wang_ka_xuan_ze, Toast.LENGTH_SHORT).show();
            return;
        }
        long availableMemoryMB = getAvailableMemoryMB();
        if (config.getLocalBufferCount() > availableMemoryMB){
            Toast.makeText(context, "手机可用内存不足，请调小本机缓冲区块大小或清理后台", Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0) {
            isRoot = false;
        } else {
            if (!Sui.init(getPackageName())){
                showInstallSuiDialog(context);
                return;
            } else {
                if (Shizuku.checkSelfPermission() != 0) {
                    Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
                    Shizuku.requestPermission(1);
                    return;
                }
            }
            isRoot = true;
        }
        bindAndStartService();
    }

    private void bindAndStartService() {
        netCardsAdapter.setEnableModify(false);
        startServerBtn.setEnabled(false);
        startServerBtn.setText(R.string.ting_zhi_fu_wu);
        if (isRoot) {
            Shizuku.bindUserService(IOService.getUserServiceArgs(context), this);
        } else {
            Intent intent = new Intent(context, IOService.class);
            bindService(intent, this, Service.BIND_AUTO_CREATE);
        }
    }

    private void unbindService() {
        if (isRoot) {
            Shizuku.unbindUserService(IOService.getUserServiceArgs(context), this, true);
        } else {
            unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IIOService iioService = IIOService.Stub.asInterface(service);
        server = new HFXServer(iioService);
        StartServerCallback callback = new StartServerCallback() {
            @Override
            public void onBindFailed(int port) {
                Toast.makeText(context, getString(R.string.service_start_failed,port), Toast.LENGTH_SHORT).show();
                netCardsAdapter.setEnableModify(true);
                startServerBtn.setEnabled(true);
                startServerBtn.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
                server = null;
            }

            @Override
            public void onStatedServer() {
                startServerBtn.setEnabled(true);
                Toast.makeText(context, R.string.fu_wu_yi_qi_dong, Toast.LENGTH_SHORT).show();
                for (ServerNetInterface selectedInterface : netCardsAdapter.getSelectedInterfaces()) {
                    netCardsAdapter.changeItemState(selectedInterface.name,getString(R.string.deng_dai_lian_jie));
                }
            }

            @Override
            public void onAccepted(String name) {
                netCardsAdapter.changeItemState(name,getString(R.string.yi_lian_jie));
            }

            @Override
            public void onAcceptFailed(String name) {
                netCardsAdapter.changeItemState(name,getString(R.string.lian_jie_shi_bai));
            }

            @Override
            public void onPcOOM() {
                Toast.makeText(context, "电脑端内存不足创建缓冲区块失败，请看控制台信息", Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onMeOOM(int created, int localBufferCount) {
                Toast.makeText(context, String.format(Locale.getDefault(),
                        "缓冲区块创建失败，已创建[%dMB/%dMB]，请尝试调小缓冲区块数或清理后台",
                        created,localBufferCount), Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onConnectSuccess() {
                toTransfer.setEnabled(true);
                //设置静态实例，使得传输Activity能使用
                HFXServer.instance = server;
                //TODO 国际化
                startServerBtn.setText("断开连接");
            }

            @Override
            public void onError(Throwable th) {
                Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
                changeToStartState();
            }
        };
        server.startServer(config.getServerPort(),netCardsAdapter.getSelectedInterfaces(),
                config.getLocalBufferCount(),config.getRemoteBufferCount(), callback);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        System.out.println("onServiceDisconnected");
    }


    private boolean checkPermissionOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 检查是否有所有文件访问权限
            if (Environment.isExternalStorageManager()) {
                // 已经获得文件访问权限
                return true;
            } else {
                // 请求文件访问权限
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                //添加包名，不然要在设置中翻列表
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                return false;
            }
        } else {
            // 检查是否有存储权限
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // 已经获得存储权限
                return true;
            } else {
                // 请求存储权限
                ActivityCompat.requestPermissions(this, new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_TRANSFER) {
            if (resultCode == RESULT_CODE_SERVER_DISCONNECT){
                disconnect(false);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.github) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.update) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer/releases"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.buffer_count){
            View view = View.inflate(context, R.layout.dialog_buffer_settings, null);
            EditText editLocalCount = view.findViewById(R.id.edit_local_buffer_count);
            EditText editRemoteCount = view.findViewById(R.id.edit_remote_buffer_count);
            editLocalCount.setText(String.valueOf(config.getLocalBufferCount()));
            editRemoteCount.setText(String.valueOf(config.getRemoteBufferCount()));
            editLocalCount.setHint("当前最高可设置：" + getAvailableMemoryMB());

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("设置缓冲区块数（1MB每块）")
                    .setView(view)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(v -> {
                    String localCountStr = editLocalCount.getText().toString();
                    String remoteCountStr = editRemoteCount.getText().toString();

                    // 清除之前的错误提示
                    editLocalCount.setError(null);
                    editRemoteCount.setError(null);

                    // 校验是否为空
                    if (localCountStr.isEmpty()) {
                        editLocalCount.setError("缓冲区块数不能为空");
                        return;
                    }
                    if (remoteCountStr.isEmpty()) {
                        editRemoteCount.setError("缓冲区块数不能为空");
                        return;
                    }

                    int localCount = Integer.parseInt(localCountStr);
                    int remoteCount = Integer.parseInt(remoteCountStr);

                    // 校验是否大于 16
                    if (localCount <= 16) {
                        editLocalCount.setError("最小可设置 16");
                        return;
                    }
                    if (remoteCount <= 16) {
                        editRemoteCount.setError("最小可设置 16");
                        return;
                    }

                    config.setLocalBufferCount(localCount);
                    config.setRemoteBufferCount(remoteCount);
                    dialog.dismiss();
                });
            });

            dialog.show();
        }

        return super.onOptionsItemSelected(item);
    }

    public void showInstallSuiDialog(Context context) {
        // 创建对话框
        new AlertDialog.Builder(context)
                .setTitle(R.string.wei_an_zhuang_sui_mo_kuai)
                .setMessage(R.string.install_sui_hint)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    // 打开指定链接
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Sui/releases"));
                    context.startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeToStartState(){
        netCardsAdapter.setEnableModify(true);
        startServerBtn.setEnabled(true);
        startServerBtn.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
        server = null;
        unbindService();
    }

    private void disconnect(boolean toast){
        startServerBtn.setEnabled(false);
        if (HFXServer.instance == null){
            server.closeServerSocket();
        } else {
            server.disconnect(new BackstageTask.BaseEventHandler() {
                @Override
                public void onComplete() {
                    if (toast){
                        Toast.makeText(context, R.string.fu_wu_yi_guan_bi, Toast.LENGTH_SHORT).show();
                    }
                    unbindService();
                    netCardsAdapter.setEnableModify(true);
                    toTransfer.setEnabled(false);
                    startServerBtn.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
                    startServerBtn.setEnabled(true);
                    HFXServer.instance = null;
                    server = null;
                }
            });
        }
    }

    private long getAvailableMemoryMB(){
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected void onDestroy() {
        if (HFXServer.instance != null){
            HFXServer.instance.disconnect(new BackstageTask.BaseEventHandler() {});
        }
        super.onDestroy();
    }
}