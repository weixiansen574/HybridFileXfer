package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import rikka.shizuku.Shizuku;
import top.weixiansen574.async.TaskManger;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableFileTransferEvent;
import top.weixiansen574.hybridfilexfer.droidcore.ParcelableTransferredBytesInfo;

public class TransferActivity extends AppCompatActivity implements ServiceConnection {
    private boolean isRootMode;
    private boolean isLeftFocus = true;
    private Context context;
    private RecyclerView rv_left_files, rv_right_files;
    private ConstraintLayout shadowLeft, shadowRight;
    private FrameLayout frameLeft, frameRight;
    private IIServiceFileSelectAdapter leftRVAdapter;
    private IIServiceFileSelectAdapter rightRVAdapter;
    private Toolbar toolbar;
    private FrameLayout frameLayout;
    private View currentSelectView;
    private View leftSelectView, rightSelectView;

    private FileTransferEventMonitorThread fileTransferEventMonitorThread;
    private TransferSpeedMeterThread transferSpeedMeterThread;
    private AlertDialog errorDialog;
    private ConfigDB configDB;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        context = this;
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        frameLayout = findViewById(R.id.frame_tool);
        leftSelectView = LayoutInflater.from(context).inflate(R.layout.toolbar_select, null);
        rightSelectView = LayoutInflater.from(context).inflate(R.layout.toolbar_select, null);
        frameLeft = findViewById(R.id.frame_left);
        frameRight = findViewById(R.id.frame_right);
        shadowLeft = findViewById(R.id.inner_shadow_left);
        shadowRight = findViewById(R.id.inner_shadow_right);
        rv_left_files = findViewById(R.id.rv_left_files);
        rv_right_files = findViewById(R.id.rv_right_files);
        frameLeft.removeView(shadowLeft);

        Intent intent = getIntent();
        isRootMode = intent.getBooleanExtra("isRoot", false);


        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(context);
        linearLayoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rv_left_files.setLayoutManager(linearLayoutManager);
        linearLayoutManager = new LinearLayoutManager(context);
        ;
        linearLayoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rv_right_files.setLayoutManager(linearLayoutManager);

        bindService();
        configDB = new ConfigDB(context);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ITransferService iTransferService = ITransferService.Stub.asInterface(service);
        System.out.println("TransferActivity 已连接service，IBinder:" + service + " iTransferService:" + iTransferService);
        OnTouchListener onTouchListenerForLeft = new OnTouchListener(this, true);
        Toolbar localFileSelectToolbar = leftSelectView.findViewById(R.id.toolbar);
        Toolbar remoteFileSelectToolbar = rightSelectView.findViewById(R.id.toolbar);
        OnTouchListener onTouchListenerForRight = new OnTouchListener(this, false);
        leftRVAdapter = new LocalFileSelectAdapter(this, onTouchListenerForLeft, localFileSelectToolbar, findViewById(R.id.frame_rv_left), rv_left_files, iTransferService);
        rightRVAdapter = new RemoteFileSelectAdapter(this, onTouchListenerForRight, remoteFileSelectToolbar, findViewById(R.id.frame_rv_right), rv_right_files, iTransferService);
        rv_left_files.setOnTouchListener(onTouchListenerForLeft);
        rv_right_files.setOnTouchListener(onTouchListenerForRight);
        leftRVAdapter.setSelectModeListener(isSelectMode -> switchSelectView(isSelectMode ? leftSelectView : null));
        rightRVAdapter.setSelectModeListener(isSelectMode -> switchSelectView(isSelectMode ? rightSelectView : null));
        rv_left_files.setAdapter(leftRVAdapter);
        rv_right_files.setAdapter(rightRVAdapter);


        fileTransferEventMonitorThread = new FileTransferEventMonitorThread(this, iTransferService);
        fileTransferEventMonitorThread.setDaemon(true);
        fileTransferEventMonitorThread.setName("TEventMonitor");
        fileTransferEventMonitorThread.start();
        transferSpeedMeterThread = new TransferSpeedMeterThread(this, iTransferService);
        transferSpeedMeterThread.setName("TSpeedMeter");
        transferSpeedMeterThread.start();
        findViewById(R.id.speed_info).setOnClickListener(v -> showTransferProgressDialog());
        TextView usb_upload_speed = findViewById(R.id.usb_upload_speed);
        TextView usb_download_speed = findViewById(R.id.usb_download_speed);
        TextView wifi_upload_speed = findViewById(R.id.wifi_upload_speed);
        TextView wifi_download_speed = findViewById(R.id.wifi_download_speed);

        transferSpeedMeterThread.addListener(new OnInternetSpeedChangeListener() {
            @Override
            public void onInternetSpeedChanged(long usbUpSpeed, long usbDownSpeed, long wifiUpSpeed, long wifiDownSpeed) {
                usb_upload_speed.setText(Utils.formatSpeed(usbUpSpeed));
                usb_download_speed.setText(Utils.formatSpeed(usbDownSpeed));
                wifi_upload_speed.setText(Utils.formatSpeed(wifiUpSpeed));
                wifi_download_speed.setText(Utils.formatSpeed(wifiDownSpeed));
            }
        });

        leftRVAdapter.setOnToTransferListener((selectedItems, dir) -> {
            new AlertDialog.Builder(context)
                    .setTitle("确认传输")
                    .setMessage("是否将选中的 " + selectedItems.size() + " 个文件传输到电脑目录：" + rightRVAdapter.getCurrentDir())
                    .setPositiveButton("确定", (dialog, which) -> {
                        leftRVAdapter.cancelSelect();
                        showTransferProgressDialog();
                        try {
                            iTransferService.transferToPc(selectedItems, dir, rightRVAdapter.getCurrentDir());
                        } catch (RemoteException e) {
                            onServerDied();
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                    })
                    .show();
        });
        rightRVAdapter.setOnToTransferListener((selectedItems, dir) -> {
            new AlertDialog.Builder(context)
                    .setTitle("确认传输")
                    .setMessage("是否将选中的 " + selectedItems.size() + " 个文件传输到手机目录：" + leftRVAdapter.getCurrentDir())
                    .setPositiveButton("确定", (dialog, which) -> {
                        rightRVAdapter.cancelSelect();
                        showTransferProgressDialog();
                        TaskManger.start(() -> {
                            try {
                                iTransferService.transferToMe(selectedItems, dir, leftRVAdapter.getCurrentDir());
                            } catch (RemoteException e) {
                                onServerDied();
                            }
                        });
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                    })
                    .show();
        });

    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    private void bindService() {
        if (isRootMode) {
            Shizuku.bindUserService(Utils.getUserServiceArgs(context), this);
        } else {
            Intent intent = new Intent(context, TransferServices.class);
            bindService(intent, this, Service.BIND_AUTO_CREATE);
        }
    }

    private void unbindService() {
        if (isRootMode) {
            Shizuku.unbindUserService(Utils.getUserServiceArgs(context), this, false);
        } else {
            unbindService(this);
        }
    }

    public void onServerDied() {
        runOnUiThread(() -> {
            if (errorDialog == null && !isDestroyed()) {
                errorDialog = new AlertDialog.Builder(context)
                        .setTitle("发生异常")
                        .setMessage("服务端已终止（网络连接中断或服务进程被杀），请重新连接！")
                        .setCancelable(false)
                        .setPositiveButton("确定", (dialog, which) -> {
                            finish();
                        }).show();
            }
        });

    }

    public void switchTo(boolean isLeft) {
        if (isLeft) {
            //切换内阴影
            if (!this.isLeftFocus) {
                frameLeft.removeView(shadowLeft);
                frameRight.addView(shadowRight);
            }
            switchSelectView(leftRVAdapter.isSelectMode() ? leftSelectView : null);
        } else {
            if (isLeftFocus) {
                frameRight.removeView(shadowRight);
                frameLeft.addView(shadowLeft);
            }
            switchSelectView(rightRVAdapter.isSelectMode() ? rightSelectView : null);
        }
        this.isLeftFocus = isLeft;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isLeftFocus) {
                if (leftRVAdapter.isSelectMode()) {
                    leftRVAdapter.cancelSelect();
                } else {
                    leftRVAdapter.cdParent();
                }
            } else {
                if (rightRVAdapter.isSelectMode()) {
                    rightRVAdapter.cancelSelect();
                } else {
                    rightRVAdapter.cdParent();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void switchSelectView(View selectView) {
        if (currentSelectView != selectView) {
            if (currentSelectView != null) {
                frameLayout.removeView(currentSelectView);
            }
            if (selectView != null) {
                frameLayout.addView(selectView);
            }
            currentSelectView = selectView;
        }
    }


    private static class OnTouchListener implements View.OnTouchListener {

        private final TransferActivity activity;
        private final boolean isLeft;

        public OnTouchListener(TransferActivity activity, boolean isLeft) {
            this.activity = activity;
            this.isLeft = isLeft;
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                System.out.println("切换到:" + (isLeft ? "左" : "右"));
                activity.switchTo(isLeft);
            }
            return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.refresh) {
            if (leftRVAdapter == null || rightRVAdapter == null) {
                return true;
            }
            if (isLeftFocus) {
                leftRVAdapter.cd(leftRVAdapter.getCurrentDir());
            } else {
                rightRVAdapter.cd(rightRVAdapter.getCurrentDir());
            }
        } else if (id == R.id.bookmark_list) {
            View dialogView = View.inflate(context,R.layout.dialog_bookmarks,null);
            RecyclerView recyclerView = dialogView.findViewById(R.id.bookmark_list);
            LinearLayoutManager layoutManager = new LinearLayoutManager(context);
            layoutManager.setOrientation(RecyclerView.VERTICAL);
            recyclerView.setLayoutManager(layoutManager);
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton("关闭",null);
            if (isLeftFocus){
                builder.setTitle("本地文件夹书签");
                recyclerView.setAdapter(new LocalBookmarkAdapter(context,builder.show(),leftRVAdapter,configDB));
            } else {
                builder.setTitle("电脑文件夹书签");
                recyclerView.setAdapter(new RemoteBookmarkAdapter(context,builder.show(),rightRVAdapter,configDB));
            }

        } else if (id == R.id.add_bookmark) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (isLeftFocus) {
                builder.setTitle("确认添加到本地文件夹书签吗？")
                        .setMessage(leftRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(false,leftRVAdapter.getCurrentDir());
                        });
            } else {
                builder.setTitle("确认添加到电脑文件夹书签吗？")
                        .setMessage(rightRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(true,rightRVAdapter.getCurrentDir());
                        });
            }
            builder.setNegativeButton(R.string.cancel, null);
            builder.show();
        }
        return super.onOptionsItemSelected(item);
    }

    private void addBookmark(boolean isRemote,String path) {
        boolean exists;
        if (isRemote){
            exists = configDB.checkRemoteBookmarkExists(path);
            if (!exists) {
                configDB.addRemoteBookmark(path);
            }
        } else {
            exists = configDB.checkLocalBookmarkExists(path);
            if (!exists) {
                configDB.addLocalBookmark(path);
            }
        }
        if (!exists) {
            Toast.makeText(this, "已添加至"+(isRemote ? "电脑" : "本地")+"书签列表" ,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "书签已存在", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService();
        if (transferSpeedMeterThread != null) {
            transferSpeedMeterThread.isRun = false;
            transferSpeedMeterThread.interrupt();
        }

        if (fileTransferEventMonitorThread != null) {
            try {
                fileTransferEventMonitorThread.service.stopGetNextEvent();
            } catch (RemoteException ignored) {
            }
        }
    }

    private void showTransferProgressDialog() {
        View dialogView = View.inflate(context, R.layout.dialog_transfer_progress_2, null);
        TextView txv_usb_transfer_event = dialogView.findViewById(R.id.txv_usb_transfer_event);
        TextView txv_wifi_transfer_event = dialogView.findViewById(R.id.txv_wifi_transfer_event);
        TextView txv_usb_upload_speed = dialogView.findViewById(R.id.txv_usb_upload_speed);
        TextView txv_usb_download_speed = dialogView.findViewById(R.id.txv_usb_download_speed);
        TextView txv_wifi_upload_speed = dialogView.findViewById(R.id.txv_wifi_upload_speed);
        TextView txv_wifi_download_speed = dialogView.findViewById(R.id.txv_wifi_download_speed);

        OnFileTransferEventListener listener = new OnFileTransferEventListener() {
            @Override
            public void onEvent(ParcelableFileTransferEvent event) {
                TextView setText = null;
                if (event.getDevice() == ParcelableFileTransferEvent.DEVICE_USB) {
                    setText = txv_usb_transfer_event;
                } else if (event.getDevice() == ParcelableFileTransferEvent.DEVICE_WIFI) {
                    setText = txv_wifi_transfer_event;
                } else {
                    throw new RuntimeException("unknown device id:" + event.getDevice());
                }
                switch (event.getState()) {
                    case ParcelableFileTransferEvent.STATE_UPLOAD:
                        setText.setText("▲" + event.getDesc());
                        break;
                    case ParcelableFileTransferEvent.STATE_DOWNLOAD:
                        setText.setText("▼" + event.getDesc());
                        break;
                    case ParcelableFileTransferEvent.STATE_OVER:
                        setText.setText("■" + event.getDesc());
                        break;
                }
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("▲0.00MB/s ▼0.00MB/s")
                .setPositiveButton("收起", null)
                .show();

        OnInternetSpeedChangeListener speedChangeListener = new OnInternetSpeedChangeListener() {
            @Override
            public void onInternetSpeedChanged(long usbUpSpeed, long usbDownSpeed, long wifiUpSpeed, long wifiDownSpeed) {
                dialog.setTitle(String.format(Locale.getDefault(),
                        "▲%.2fMB/s ▼%.2fMB/s",
                        ((float) usbUpSpeed + wifiUpSpeed) / 1024 / 1024,
                        ((float) usbDownSpeed + wifiDownSpeed) / 1024 / 1024));
                txv_usb_upload_speed.setText(Utils.formatSpeed(usbUpSpeed));
                txv_usb_download_speed.setText(Utils.formatSpeed(usbDownSpeed));
                txv_wifi_upload_speed.setText(Utils.formatSpeed(wifiUpSpeed));
                txv_wifi_download_speed.setText(Utils.formatSpeed(wifiDownSpeed));
            }
        };

        fileTransferEventMonitorThread.addListener(listener);
        transferSpeedMeterThread.addListener(speedChangeListener);
        dialog.setOnDismissListener(dialog1 -> {
            fileTransferEventMonitorThread.removeListener(listener);
            transferSpeedMeterThread.removeListener(speedChangeListener);
        });
    }

    public static class FileTransferEventMonitorThread extends Thread {
        private boolean isRun;
        private TransferActivity activity;
        private ITransferService service;
        List<OnFileTransferEventListener> listeners;

        public FileTransferEventMonitorThread(TransferActivity activity, ITransferService service) {
            this.activity = activity;
            this.service = service;
            this.listeners = new ArrayList<>();
        }

        @Override
        public void run() {
            isRun = true;
            while (isRun) {
                try {
                    ParcelableFileTransferEvent nextFileTransferEvent = service.getNextFileTransferEvent();
                    if (nextFileTransferEvent != null) {
                        activity.runOnUiThread(() -> {
                            for (OnFileTransferEventListener listener : listeners) {
                                listener.onEvent(nextFileTransferEvent);
                            }
                        });
                    } else {
                        System.out.println("文件传输事件监控线程结束");
                        break;
                    }
                } catch (RemoteException e) {
                    isRun = false;
                    activity.onServerDied();
                }
            }
        }

        public void addListener(OnFileTransferEventListener eventListener) {
            listeners.add(eventListener);
        }

        public void removeListener(OnFileTransferEventListener eventListener) {
            listeners.remove(eventListener);
        }
    }

    public interface OnFileTransferEventListener {
        void onEvent(ParcelableFileTransferEvent event);
    }


    public static class TransferSpeedMeterThread extends Thread {
        private boolean isRun;
        private Activity activity;
        private ITransferService service;
        private ArrayList<OnInternetSpeedChangeListener> listeners;

        public TransferSpeedMeterThread(Activity activity, ITransferService service) {
            this.activity = activity;
            this.service = service;
            listeners = new ArrayList<>();
        }

        @Override
        public void run() {
            isRun = true;
            while (isRun) {
                try {
                    Thread.sleep(1000);
                    ParcelableTransferredBytesInfo info = service.getTransferredBytesInfo();
                    activity.runOnUiThread(() -> {
                        for (OnInternetSpeedChangeListener listener : listeners) {
                            listener.onInternetSpeedChanged(info.getUsbSentBytes(),
                                    info.getUsbReceiveBytes(),
                                    info.getWifiSentBytes(),
                                    info.getWifiReceiveBytes());
                        }
                    });
                } catch (InterruptedException | RemoteException ignored) {
                }
            }
        }

        public void addListener(OnInternetSpeedChangeListener listener) {
            listeners.add(listener);
        }

        public void removeListener(OnInternetSpeedChangeListener listener) {
            listeners.remove(listener);
        }
    }


    public interface OnInternetSpeedChangeListener {
        void onInternetSpeedChanged(long usbUpSpeed, long usbDownSpeed, long wifiUpSpeed, long wifiDownSpeed);
    }


    /*private void replaceFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(R.id.frame_tool, fragment);
        fragmentTransaction.commit();
    }*/
}
