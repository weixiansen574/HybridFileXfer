package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Objects;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.listadapter.BookmarkAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.FileSelectAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.LocalFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.RemoteFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.tasks.BTransferFileCallback;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToRemoteTask;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToShelfTask;

public class TransferActivity extends AppCompatActivity {
    private boolean isLeftFocus = true;
    private Activity context;
    //private HFXServer server;
    private HFXServer server;
    private FileSelectAdapter leftRVAdapter, rightRVAdapter;
    RecyclerView leftList;
    RecyclerView rightList;
    ConstraintLayout shadowGroupLeft;
    ConstraintLayout shadowGroupRight;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        setContentView(R.layout.activity_transfer);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        leftList = findViewById(R.id.rv_left_files);
        rightList = findViewById(R.id.rv_right_files);
        shadowGroupLeft = findViewById(R.id.shadow_group_left);
        shadowGroupRight = findViewById(R.id.shadow_group_right);
        shadowGroupLeft.setVisibility(View.INVISIBLE);
        Toolbar leftSelectToolbar = findViewById(R.id.toolbar_select_left);
        Toolbar rightSelectToolbar = findViewById(R.id.toolbar_select_right);

        server = HFXServer.instance;
        if (server == null) {
            Toast.makeText(this, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        //初始化左边
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        leftList.setLayoutManager(layoutManager);
        OnTouchListener onTouchListener = new OnTouchListener(this, true);
        leftRVAdapter = new LocalFileSelectAdapter(context, findViewById(R.id.loading_left),
                leftList, layoutManager, leftSelectToolbar, onTouchListener, server);
        leftList.setAdapter(leftRVAdapter);
        Animation animation = AnimationUtils.loadAnimation(context, R.anim.list_anim);
        LayoutAnimationController layoutAnimationController = new LayoutAnimationController(animation);
        layoutAnimationController.setOrder(LayoutAnimationController.ORDER_NORMAL);
        layoutAnimationController.setDelay(0.2f);
        leftList.setLayoutAnimation(layoutAnimationController);
        leftList.setOnTouchListener(onTouchListener);

        //初始化右边
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        rightList.setLayoutManager(layoutManager);
        onTouchListener = new OnTouchListener(this, false);
        rightRVAdapter = new RemoteFileSelectAdapter(context, findViewById(R.id.loading_right),
                rightList, layoutManager, rightSelectToolbar, onTouchListener, server);
        rightList.setAdapter(rightRVAdapter);
        animation = AnimationUtils.loadAnimation(context, R.anim.list_anim);
        layoutAnimationController = new LayoutAnimationController(animation);
        layoutAnimationController.setOrder(LayoutAnimationController.ORDER_NORMAL);
        layoutAnimationController.setDelay(0.2f);
        rightList.setLayoutAnimation(layoutAnimationController);
        rightList.setOnTouchListener(onTouchListener);

        leftRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_chuan_shu)
                .setMessage(getString(R.string.chuan_shu_dao_dian_nao_mu_lu, selectedItems.size(), rightRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    leftRVAdapter.cancelSelect();
                    sendFilesToRemote(selectedItems, dir, rightRVAdapter.getCurrentDirectory());
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        rightRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_chuan_shu)
                .setMessage(getString(R.string.chuan_shu_dao_shou_ji_mu_lu, selectedItems.size(), leftRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    rightRVAdapter.cancelSelect();
                    sendFilesToShelf(selectedItems, leftRVAdapter.getCurrentDirectory(), dir);
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir) {
        TransferDialogHandler handler = new TransferDialogHandler(context, true, server.getConnectionListINames(), rightRVAdapter);
        new SendFilesToRemoteTask(handler, server, files, localDir, remoteDir).execute();
    }

    private void sendFilesToShelf(List<RemoteFile> selectedItems, Directory localDir, Directory remoteDir) {
        TransferDialogHandler handler = new TransferDialogHandler(context, false, server.getConnectionListINames(), leftRVAdapter);
        new SendFilesToShelfTask(handler, server, selectedItems, localDir, remoteDir).execute();
    }


    public void switchTo(boolean isLeft) {
        if (isLeft) {
            //切换内阴影
            if (!this.isLeftFocus) {
                shadowGroupLeft.setVisibility(View.INVISIBLE);
                shadowGroupRight.setVisibility(View.VISIBLE);
            }
            if (leftRVAdapter.isSelectMode()) {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        } else {
            if (isLeftFocus) {
                shadowGroupLeft.setVisibility(View.VISIBLE);
                shadowGroupRight.setVisibility(View.INVISIBLE);
            }
            if (rightRVAdapter.isSelectMode()) {
                rightRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        }
        this.isLeftFocus = isLeft;
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
                leftRVAdapter.refresh();
            } else {
                rightRVAdapter.refresh();
            }
            return true;
        } else if (id == R.id.bookmark_list) {
            View dialogView = View.inflate(context, R.layout.dialog_bookmarks, null);
            RecyclerView recyclerView = dialogView.findViewById(R.id.bookmark_list);
            LinearLayoutManager layoutManager = new LinearLayoutManager(context);
            layoutManager.setOrientation(RecyclerView.VERTICAL);
            recyclerView.setLayoutManager(layoutManager);
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton(R.string.close, null);
            if (isLeftFocus) {
                builder.setTitle(R.string.ben_di_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), leftRVAdapter, false));
            } else {
                builder.setTitle(R.string.dian_nao_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), rightRVAdapter, true));
            }
            return true;
        } else if (id == R.id.add_bookmark) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (isLeftFocus) {
                builder.setTitle(R.string.que_ren_tian_jia_dao_ben_di_wen_jian_jia_shu_qian_ma)
                        .setMessage(leftRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(false, leftRVAdapter.getCurrentDir());
                        });
            } else {
                builder.setTitle(R.string.que_ren_tian_jia_dao_dian_nao_wen_jian_jia_shu_qian_ma)
                        .setMessage(rightRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(true, rightRVAdapter.getCurrentDir());
                        });
            }
            builder.setNegativeButton(R.string.cancel, null);
            builder.show();
            return true;
        } else if (id == R.id.mkdir) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            String title = isLeftFocus
                    ? context.getString(R.string.create_folder_mobile)
                    : context.getString(R.string.create_folder_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_wen_jian_ming));
                    return;
                }
                if (Utils.containsIllegalCharacters(input)) {
                    editText.setError(context.getString(R.string.invalid_filename_characters));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.mkdir(leftRVAdapter.getCurrentDir(), input);
                } else {
                    rightRVAdapter.mkdir(rightRVAdapter.getCurrentDir(), input);
                }
            });
        } else if (id == R.id.jump) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            editText.setText(isLeftFocus ? leftRVAdapter.getCurrentDir() : rightRVAdapter.getCurrentDir());
            String title = isLeftFocus
                    ? context.getString(R.string.jump_path_mobile)
                    : context.getString(R.string.jump_path_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_lu_jing));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.jump(input);
                } else {
                    rightRVAdapter.jump(input);
                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void addBookmark(boolean isRemote, String path) {
        boolean exists;
        ConfigDB configDB = ConfigDB.getInstance(context);
        if (isRemote) {
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
            String message = isRemote
                    ? getString(R.string.bookmark_added_remote)
                    : getString(R.string.bookmark_added_local);

            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.shu_qian_yi_cun_zai, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return super.onCreateOptionsMenu(menu);
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
                activity.switchTo(isLeft);
            }
            return false;
        }
    }

    private static class TransferDialogHandler implements BTransferFileCallback {
        Activity context;
        TransferDialog transferDialog;
        FileSelectAdapter adapter;

        public TransferDialogHandler(Activity context, boolean isUpload, List<String> channelNames, FileSelectAdapter adapter) {
            this.context = context;
            this.adapter = adapter;
            transferDialog = new TransferDialog(context, isUpload, channelNames);
            transferDialog.show();
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
            transferDialog.showEvent(iName, String.format("▲ [%s/%s] %s",
                    Utils.formatFileSize(targetSize),
                    Utils.formatFileSize(totalSize),
                    path));
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
            transferDialog.showEvent(iName, String.format("▼ [%s/%s] %s",
                    Utils.formatFileSize(targetSize),
                    Utils.formatFileSize(totalSize),
                    path));
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            transferDialog.showSpeeds(trafficInfoList);
        }


        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            transferDialog.showEvent(iName, String.format("传输完毕！平均速度：%s",
                    time == 0 ? "∞" : Utils.formatSpeed(traffic / time * 1000)));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            switch (errorType) {
                case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                    transferDialog.showEvent(iName, message);
                    break;
                case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                    transferDialog.showEvent(iName, context.getString(R.string.transmission_interrupted));
                    break;
                case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                    transferDialog.showEvent(iName, "读取文件时出错");
                    break;
                case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                    transferDialog.showEvent(iName, "写入文件时出错");
                    break;
            }
        }

        @Override
        public void onReadFileError(String message) {
            transferDialog.setTitle("传输失败");
            transferDialog.setCloseBtnEnable(true);
            new AlertDialog.Builder(context)
                    .setTitle("读取文件时发生错误")
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }

        @Override
        public void onWriteFileError(String message) {
            transferDialog.setTitle("传输失败");
            transferDialog.setCloseBtnEnable(true);
            new AlertDialog.Builder(context)
                    .setTitle("写入文件时发生错误")
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            //文件传输完成，平均总速度：
            transferDialog.complete(isUpload, traffic, time);
            adapter.refresh();
        }

        @Override
        public void onIncomplete() {
            Toast.makeText(context, R.string.chuan_shu_shi_fa_sheng_yi_chang, Toast.LENGTH_LONG).show();
            transferDialog.setCloseBtnEnable(true);
            transferDialog.setButton(context.getString(R.string.exit), v -> context.finish());
            context.setResult(MainActivity.RESULT_CODE_SERVER_DISCONNECT);
        }

        @Override
        public void onError(Throwable th) {
            transferDialog.dismiss();
            new AlertDialog.Builder(context)
                    .setTitle("发生错误")
                    .setMessage(th.toString())
                    .setPositiveButton(R.string.ok, null)
                    .setOnDismissListener(dialog -> context.finish())
                    .show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isLeftFocus) {
                if (leftRVAdapter.isSelectMode()) {
                    leftRVAdapter.cancelSelect();
                } else {
                    leftRVAdapter.back();
                }
            } else {
                if (rightRVAdapter.isSelectMode()) {
                    rightRVAdapter.cancelSelect();
                } else {
                    rightRVAdapter.back();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}