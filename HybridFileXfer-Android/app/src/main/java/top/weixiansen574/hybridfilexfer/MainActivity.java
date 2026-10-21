package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.listadapter.NetCardsAdapter;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ServerSession.Listener {
    public static final int REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = 1;
    public static final int REQUEST_CODE_TRANSFER = 2;
    public static final int RESULT_CODE_SERVER_DISCONNECT = 1;
    private NetCardsAdapter netCardsAdapter;
    private Spinner spinnerMode;
    Button startServerBtn;
    Button toTransfer;
    Context context;
    public static final String ACTION_REQUEST_START = "top.weixiansen574.hybridfilexfer.action.REQUEST_START";
    private ServerSession session;
    private String requestedSecret;
    private int requestedPort;
    private boolean startApproved;
    private AlertDialog pendingStartDialog;
    private String lastStartRequestId;
    private android.widget.TextView sessionDetails;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (startApproved && grantResult == PackageManager.PERMISSION_GRANTED) startServer();
        else startApproved = false;
    };
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        config = Config.getInstance(context);
        session = ServerSession.get(this);
        sessionDetails = findViewById(R.id.session_details);
        Shizuku.addRequestPermissionResultListener(permissionListener);
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
        session.attach(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5741);
        }
        if (savedInstanceState == null) handleStartIntent(getIntent());
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server) {
            if (!session.active) {
                startServer();
            } else {
                disconnect(true);
            }
        } else if (id == R.id.to_transfer) {
            startActivityForResult(new Intent(context, TransferActivity.class), REQUEST_CODE_TRANSFER);
        } else if (id == R.id.refresh) {
            if (!session.active && netCardsAdapter != null) {
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
        if (session.active) return;
        if (netCardsAdapter == null) {
            Toast.makeText(this, R.string.io_start_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (ClientSession.get(this).active) {
            Toast.makeText(this, R.string.session_already_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (!startApproved) {
            confirmStart(top.weixiansen574.hybridfilexfer.security.Pairing.newSecret(), config.getServerPort());
            return;
        }
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
        int mode = spinnerMode.getSelectedItemPosition();
        if (mode != Config.MODE_NORMAL && mode != Config.MODE_ROOT_SU && checkShizukuOrReq(mode)) return;
        startApproved = false;
        session.start(mode, requestedPort, requestedSecret, selectedInterfaces,
                config.getLocalBufferCount(), config.getRemoteBufferCount());
        requestedSecret = null;
    }

    @Override public void onSessionChanged() {
        if (netCardsAdapter == null) return;
        netCardsAdapter.setEnableModify(!session.active);
        spinnerMode.setEnabled(!session.active);
        startServerBtn.setEnabled(true);
        startServerBtn.setText(session.active ? R.string.ting_zhi_fu_wu : R.string.start_service_and_wait_onnect);
        toTransfer.setEnabled(session.connected);
        String details = session.message;
        if (session.active) details += "\n" + getString(R.string.pairing_code) + ": " + session.pairingSecret;
        sessionDetails.setText(details);
        for (java.util.Map.Entry<String, String> item : session.interfaces.entrySet()) {
            netCardsAdapter.changeItemState(item.getKey(), item.getValue());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStartIntent(intent);
    }

    private void handleStartIntent(Intent intent) {
        if (intent == null || !ACTION_REQUEST_START.equals(intent.getAction())) return;
        String requestId = intent.getStringExtra("request_id");
        String secret = intent.getStringExtra("pairing_secret");
        int port = intent.getIntExtra("server_port", config.getServerPort());
        // Consume once. Extras are never persisted or logged.
        intent.removeExtra("pairing_secret");
        intent.setAction(Intent.ACTION_MAIN);
        if (requestId != null && requestId.equals(lastStartRequestId)) return;
        lastStartRequestId = requestId;
        if (session.active || ClientSession.get(this).active) {
            Toast.makeText(this, R.string.session_already_running, Toast.LENGTH_LONG).show();
            onSessionChanged();
            return;
        }
        try {
            secret = secret == null ? top.weixiansen574.hybridfilexfer.security.Pairing.newSecret()
                    : top.weixiansen574.hybridfilexfer.security.Pairing.validateSecret(secret);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException();
            confirmStart(secret, port);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, R.string.invalid_connection_settings, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmStart(String secret, int port) {
        startApproved = false;
        requestedSecret = null;
        if (pendingStartDialog != null) pendingStartDialog.dismiss();
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);
        android.widget.TextView description = new android.widget.TextView(this);
        description.setText(getString(R.string.confirm_start_description) + "\n\n" +
                getString(R.string.pairing_code) + ": " + secret);
        description.setTextIsSelectable(true);
        form.addView(description);
        EditText portInput = new EditText(this);
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setHint(R.string.server_port);
        portInput.setText(String.valueOf(port));
        form.addView(portInput);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.confirm_start_title)
                .setView(form).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok, null).show();
        pendingStartDialog = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int selectedPort = Integer.parseInt(portInput.getText().toString());
                if (selectedPort < 1024 || selectedPort > 65535) throw new NumberFormatException();
                requestedPort = selectedPort;
                requestedSecret = secret;
                config.setServerPort(selectedPort);
                startApproved = true;
                dialog.dismiss();
                startServer();
            } catch (NumberFormatException e) {
                portInput.setError(getString(R.string.invalid_connection_settings));
            }
        });
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
        if (requestCode == REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION && startApproved) {
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) startServer();
            else startApproved = false;
        }
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
        } else if (itemId == R.id.open_wifi_p2p_settings) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.Settings$WifiP2pSettingsActivity");
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
        if (ClientSession.get(this).active) {
            startActivity(new Intent(this, ClientActivity.class));
            return;
        }
        if (session.active) {
            Toast.makeText(this, R.string.session_already_running, Toast.LENGTH_LONG).show();
            return;
        }
        //客户端一样要申请存储权限
        if (checkPermissionOrRequest()) {
            return;
        }
        View view = View.inflate(context, R.layout.dialog_connect_server, null);
        EditText editIp = view.findViewById(R.id.edit_server_controller_ip);
        editIp.setText(config.getConnectServerControllerIp());
        EditText editMainDir = view.findViewById(R.id.edit_home_dir);
        editMainDir.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        EditText pairingInput = view.findViewById(R.id.edit_pairing_code);
        EditText portInput = view.findViewById(R.id.edit_server_port);
        portInput.setText(String.valueOf(config.getServerPort()));
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
            if (mode != Config.MODE_NORMAL && mode != Config.MODE_ROOT_SU) {
                if (checkShizukuOrReq(mode)) {
                    return;
                }
            }
            String pairing;
            int port;
            try {
                pairing = top.weixiansen574.hybridfilexfer.security.Pairing.validateSecret(pairingInput.getText().toString());
                port = Integer.parseInt(portInput.getText().toString());
                if (port < 1024 || port > 65535) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                pairingInput.setError(getString(R.string.invalid_connection_settings));
                return;
            }
            config.setConnectServerControllerIp(ip);
            config.setClientIOMode(mode);
            Intent intent = new Intent(context, ClientActivity.class);
            intent.putExtra("pairing_secret", pairing);
            intent.putExtra("server_port", port);
            intent.putExtra("io_mode", spinner.getSelectedItemPosition());
            intent.putExtra("controller_ip", ip);
            intent.putExtra("home_dir", editMainDir.getText().toString());
            startActivity(intent);
            dialog.dismiss();
        });
    }

    public boolean checkShizukuOrReq(int mode) {
        try {
            return checkShizukuPermission(mode);
        } catch (RuntimeException e) {
            // The Shizuku process may die between pingBinder and the permission/UID RPC.
            startApproved = false;
            Toast.makeText(this, R.string.io_disconnected, Toast.LENGTH_LONG).show();
            return true;
        }
    }

    private boolean checkShizukuPermission(int mode) {
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
                        Intent intent = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.please_install_shizuku, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        context.startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void disconnect(boolean toast) {
        session.stop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                     @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 0 && startApproved) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startServer();
            else startApproved = false;
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

    @Override protected void onStart() {
        super.onStart();
        if (session != null) session.attach(this);
    }
    @Override protected void onStop() {
        if (session != null) session.detach(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        session.detach(this);
        if (pendingStartDialog != null) pendingStartDialog.dismiss();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}
