package top.weixiansen574.hybridfilexfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
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
            Toast.makeText(this, "发生错误，无法获取网卡列表，异常信息："+e.getMessage(), Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(context, "加载网卡列表失败，异常信息："+e, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, "请先停止服务端再刷新网卡列表", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(context, "需要文件读写权限", Toast.LENGTH_LONG).show();
            return;
        }
        List<ServerNetInterface> selectedInterfaces = netCardsAdapter.getSelectedInterfaces();
        if (selectedInterfaces == null){
            return;
        }
        if (selectedInterfaces.isEmpty()){
            Toast.makeText(context, "没有网卡选择", Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0){
            isRoot = false;
        } else {
            if (!Sui.init(getPackageName())){
                //TODO 对话框提示去安装
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
                Toast.makeText(context, "服务已启动", Toast.LENGTH_SHORT).show();
                for (ServerNetInterface selectedInterface : netCardsAdapter.getSelectedInterfaces()) {
                    netCardsAdapter.changeItemState(selectedInterface.name,"等待连接");
                }
            }

            @Override
            public void onBindFailed(int port) {
                unbindService();
                Toast.makeText(context, "启动服务失败，端口："+port+" 被占用", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onConnectSuccess() {
                toTransfer.setEnabled(true);
            }

            @Override
            public void onAccepted(String name) {
                netCardsAdapter.changeItemState(name,"已连接");
            }

            @Override
            public void onAcceptFailed(String name) {
                netCardsAdapter.changeItemState(name,"连接失败");
            }

            @Override
            public void onServerClose() {
                Toast.makeText(context, "服务已关闭", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Throwable th) {
                Toast.makeText(context, "服务已停止", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onComplete() {
                /*new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ArrayList<String> files = new ArrayList<>();
                        files.add("/storage/emulated/0/DCIM/Camera/VID_20240801_073044.mp4");
                        server.sendFilesToRemote(files,"/storage/emulated/0/DCIM/Camera/","D:\\文件传输测试");
                    }
                }).start();*/

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
            startServer.setText("启动服务器并等待连接");
        } else {
            netCardsAdapter.setEnableModify(false);
            startServer.setText("停止服务");
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
}