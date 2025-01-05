package top.weixiansen574.hybridfilexfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;
import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.listadapter.NetCardsAdapter;
import top.weixiansen574.hybridfilexfer.tasks.StartServerTask;

public class MainActivity extends AppCompatActivity implements View.OnClickListener,ServiceConnection{
    public static final int CODE_REQUEST_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = 1;
    public static final int CODE_TRANSFER = 2;
    private NetCardsAdapter netCardsAdapter;
    private Spinner spinnerMode;
    Button startServer;
    Button toTransfer;
    Context context;
    private boolean isRoot = false;
    private boolean state = true;
    private Config config;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        config = Config.getInstance(context);
        startServer = findViewById(R.id.start_server);
        startServer.setOnClickListener(this);
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
            startServer.setEnabled(false);
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


       /* ArrayList<String> iNames = new ArrayList<>();
        iNames.add("USB_ADB");
        iNames.add("wlan0");
        iNames.add("wlan1");
        TransferDialog transferDialog = new TransferDialog(context, iNames);
        transferDialog.show();*/
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server){
            if (state) {
                startServer();
            } else {
                stopServer();
            }
        } else if (id == R.id.to_transfer){
            startActivityForResult(new Intent(context, TransferActivity.class),1);
        } else if (id == R.id.refresh){
            if (state){
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

    private void stopServer() {
        if (HFXSService.server != null) {
            unbindService();
            changeState(true);
        }
    }


    public void startServer(){
        if (!checkPermissionOrRequest()) {
            Toast.makeText(context, R.string.xu_yao_wen_jian_du_xie_quan_xian, Toast.LENGTH_LONG).show();
            return;
        }
        List<ServerNetInterface> selectedInterfaces = netCardsAdapter.getSelectedInterfaces();
        if (selectedInterfaces == null){
            return;
        }
        if (selectedInterfaces.isEmpty()){
            Toast.makeText(context, R.string.mei_you_wang_ka_xuan_ze, Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0){
            isRoot = false;
        } else {
            if (!Sui.init(getPackageName())){
                showInstallSuiDialog(context);
                return;
            } else {
                if(Shizuku.checkSelfPermission() != 0){
                    Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
                    Shizuku.requestPermission(1);
                    return;
                }
            }
            isRoot = true;
        }
        bindAndStartService();
    }

    private void bindAndStartService(){
        changeState(false);
        if (isRoot){
            Shizuku.bindUserService(HFXSService.getUserServiceArgs(context), this);
        } else {
            Intent intent = new Intent(context, HFXSService.class);
            bindService(intent,this,Service.BIND_AUTO_CREATE);
        }
    }

    private void unbindService(){
        toTransfer.setEnabled(false);
        if (isRoot){
            Shizuku.unbindUserService(HFXSService.getUserServiceArgs(context),this,true);
        } else {
            unbindService(this);
        }
        HFXSService.server = null;
        changeState(true);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IHFXService ihfxService = IHFXService.Stub.asInterface(service);
        HFXServer server = new HFXServer(ihfxService);
        HFXSService.server = server;
        List<ServerNetInterface> selectedInterfaces = netCardsAdapter.getSelectedInterfaces();

        new StartServerTask(new StartServerTask.EventHandler() {
            @Override
            public void onStatedServer() {
                Toast.makeText(context, R.string.fu_wu_yi_qi_dong, Toast.LENGTH_SHORT).show();
                for (ServerNetInterface selectedInterface : netCardsAdapter.getSelectedInterfaces()) {
                    netCardsAdapter.changeItemState(selectedInterface.name,getString(R.string.deng_dai_lian_jie));
                }
            }

            @Override
            public void onBindFailed(int port) {
                unbindService();
                Toast.makeText(context, getString(R.string.service_start_failed,port), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onConnectSuccess() {
                toTransfer.setEnabled(true);
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
            public void onServerClose() {
                Toast.makeText(context, R.string.fu_wu_yi_guan_bi, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Throwable th) {
                Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onComplete() {

            }
        },server,selectedInterfaces).execute();


    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (HFXSService.server != null) {
            toTransfer.setEnabled(false);
            HFXSService.server = null;
            changeState(true);
        }
        System.out.println("onServiceDisconnected");
    }

    private void changeState(boolean state){
        this.state = state;
        if (state){
            netCardsAdapter.setEnableModify(true);
            startServer.setText(R.string.qi_dong_fu_wu_qi_bing_deng_dai_lian_jie);
        } else {
            netCardsAdapter.setEnableModify(false);
            startServer.setText(R.string.ting_zhi_fu_wu);
        }
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
                startActivityForResult(intent, CODE_REQUEST_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
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
        if (requestCode == CODE_TRANSFER){
            if (data == null){
                return;
            }
            if (data.getBooleanExtra("shutdown_server", false)) {
                unbindService();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HFXServer server = HFXSService.server;
        if (server != null) {
            if (server.isFailed()){
                System.out.println("服务已失效，关闭服务");
                unbindService();
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
        if (itemId == R.id.github){
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.update) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer/releases"));
            startActivity(intent);
            return true;
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

}