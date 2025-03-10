package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
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

import rikka.shizuku.Shizuku;
import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.droidcore.StartServerTask;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
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
    private boolean isShizuku = false;
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
        if (checkPermissionOrRequest()) {
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
        if (config.getLocalBufferCount() > availableMemoryMB) {
            Toast.makeText(context, R.string.shou_ji_ke_yong_nei_cun_bu_zu, Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0) {
            isShizuku = false;
        } else {
            if (checkShizukuOrReq(spinnerMode.getSelectedItemPosition())) {
                return;
            }
            isShizuku = true;
        }
        bindAndStartService();
    }

    private void bindAndStartService() {
        netCardsAdapter.setEnableModify(false);
        startServerBtn.setEnabled(false);
        startServerBtn.setText(R.string.ting_zhi_fu_wu);
        if (isShizuku) {
            Shizuku.bindUserService(IOService.getUserServiceArgs(context), this);
        } else {
            Intent intent = new Intent(context, IOService.class);
            bindService(intent, this, Service.BIND_AUTO_CREATE);
        }
    }

    private void unbindService() {
        if (isShizuku) {
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
                Toast.makeText(context, getString(R.string.service_start_failed, port), Toast.LENGTH_SHORT).show();
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
                    netCardsAdapter.changeItemState(selectedInterface.name, getString(R.string.deng_dai_lian_jie));
                }
            }

            @Override
            public void onAccepted(String name) {
                netCardsAdapter.changeItemState(name, getString(R.string.yi_lian_jie));
            }

            @Override
            public void onAcceptFailed(String name) {
                netCardsAdapter.changeItemState(name, getString(R.string.lian_jie_shi_bai));
            }

            @Override
            public void onPcOOM() {
                Toast.makeText(context, R.string.dui_fang_nei_cun_bu_zu, Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onMeOOM(int created, int localBufferCount) {
                Toast.makeText(context, getString(R.string.buffer_block_creation_failed_toast,
                        created, localBufferCount), Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onConnectSuccess() {
                toTransfer.setEnabled(true);
                //设置静态实例，使得传输Activity能使用
                HFXServer.instance = server;
                //TODO 国际化
                startServerBtn.setText(R.string.duan_kai_lian_jie);
            }

            @Override
            public void onError(Throwable th) {
                Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
                changeToStartState();
            }
        };
        new StartServerTask(callback,server,config.getServerPort(), netCardsAdapter.getSelectedInterfaces(),
                config.getLocalBufferCount(), config.getRemoteBufferCount()).execute();
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
                return false;
            } else {
                // 请求文件访问权限
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                //添加包名，不然要在设置中翻列表
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                return true;
            }
        } else {
            // 检查是否有存储权限
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // 已经获得存储权限
                return false;
            } else {
                // 请求存储权限
                ActivityCompat.requestPermissions(this, new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return true;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_TRANSFER) {
            if (resultCode == RESULT_CODE_SERVER_DISCONNECT) {
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
        } else if (itemId == R.id.buffer_count) {
            View view = View.inflate(context, R.layout.dialog_buffer_settings, null);
            EditText editLocalCount = view.findViewById(R.id.edit_local_buffer_count);
            EditText editRemoteCount = view.findViewById(R.id.edit_remote_buffer_count);
            editLocalCount.setText(String.valueOf(config.getLocalBufferCount()));
            editRemoteCount.setText(String.valueOf(config.getRemoteBufferCount()));
            editLocalCount.setHint(getString(R.string.dang_qian_zui_gao_ke_she_zhi,getAvailableMemoryMB()));

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.she_zhi_huan_chong_qu_kuai_shu)
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
                        editLocalCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }
                    if (remoteCountStr.isEmpty()) {
                        editRemoteCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }

                    int localCount = Integer.parseInt(localCountStr);
                    int remoteCount = Integer.parseInt(remoteCountStr);

                    // 校验是否大于 16
                    if (localCount <= 16) {
                        editLocalCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }
                    if (remoteCount <= 16) {
                        editRemoteCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }

                    config.setLocalBufferCount(localCount);
                    config.setRemoteBufferCount(remoteCount);
                    dialog.dismiss();
                });
            });

            dialog.show();
        } else if (itemId == R.id.client) {
            startClient();
        }

        return super.onOptionsItemSelected(item);
    }

    private void startClient() {
        //客户端一样要申请存储权限
        if (checkPermissionOrRequest()) {
            return;
        }
        View view = View.inflate(context, R.layout.dialog_connect_server, null);
        EditText editIp = view.findViewById(R.id.edit_server_controller_ip);
        editIp.setText(config.getConnectServerControllerIp());
        EditText editMainDir = view.findViewById(R.id.edit_home_dir);
        editMainDir.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        Spinner spinner = view.findViewById(R.id.spinner_mode);
        spinner.setSelection(config.getClientIOMode());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setTitle(R.string.connect_to_server)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = editIp.getText().toString();
            if (TextUtils.isEmpty(ip)) {
                editIp.setError(getString(R.string.please_enter_the_server_ip));
                return;
            }
            if (TextUtils.isEmpty(editMainDir.getText())) {
                editMainDir.setError(getString(R.string.please_enter_the_home_dir));
                return;
            }
            int mode = spinner.getSelectedItemPosition();
            if (mode != 0) {
                if (checkShizukuOrReq(mode)) {
                    return;
                }
            }
            config.setConnectServerControllerIp(ip);
            config.setClientIOMode(mode);
            Intent intent = new Intent(context, ClientActivity.class);
            intent.putExtra("io_mode", spinner.getSelectedItemPosition());
            intent.putExtra("controller_ip", ip);
            intent.putExtra("home_dir", editMainDir.getText().toString());
            startActivity(intent);
            dialog.dismiss();
        });
    }

    public boolean checkShizukuOrReq(int mode) {
        if (!Shizuku.pingBinder()) {
            showInstallShizukuDialog(context);
            return true;
        } else {
            if (Shizuku.checkSelfPermission() != 0) {
                Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
                Shizuku.requestPermission(1);
                return true;
            }
        }
        System.out.println("Shizuku UID:" + Shizuku.getUid());
        if (mode == 1) {
            if (Shizuku.getUid() != 0) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_root)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        } else if (mode == 2) {
            if (Shizuku.getUid() != 2000) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_adb)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        }
        return false;
    }

    public void showInstallShizukuDialog(Context context) {
        // 创建对话框
        new AlertDialog.Builder(context)
                .setTitle(R.string.shizuku_not_running)
                .setMessage(R.string.shizuku_not_running_message)
                .setPositiveButton(R.string.open_shizuku, (dialog, which) -> {
                    try {
                        ComponentName componentName = new ComponentName("moe.shizuku.privileged.api", "moe.shizuku.starter.MainActivity");
                        Intent intent = new Intent();
                        intent.setComponent(componentName);
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.please_install_shizuku, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        context.startActivity(intent);
                    }
                })
                .setNeutralButton(R.string.install_sui, (dialog, which) -> {
                    // 打开指定链接
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Sui/releases"));
                    context.startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeToStartState() {
        netCardsAdapter.setEnableModify(true);
        startServerBtn.setEnabled(true);
        startServerBtn.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
        server = null;
        unbindService();
    }

    private void disconnect(boolean toast) {
        startServerBtn.setEnabled(false);
        if (HFXServer.instance == null) {
            server.closeServerSocket();
        } else {
            server.disconnect(new BackstageTask.BaseEventHandler() {
                @Override
                public void onComplete() {
                    if (toast) {
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

    private long getAvailableMemoryMB() {
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
        if (HFXServer.instance != null) {
            HFXServer.instance.disconnect(new BackstageTask.BaseEventHandler() {
            });
        }
        super.onDestroy();
    }
}