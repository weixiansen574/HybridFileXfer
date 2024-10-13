package top.weixiansen574.hybridfilexfer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import java.util.List;
import java.util.Objects;

import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;
import top.weixiansen574.hybridfilexfer.listadapter.LocalFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.listadapter.BookmarkAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.FileSelectAdapter;
import top.weixiansen574.hybridfilexfer.listadapter.RemoteFileSelectAdapter;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToRemoteTask;
import top.weixiansen574.hybridfilexfer.tasks.SendFilesToShelfTask;

public class TransferActivity extends AppCompatActivity {
    private boolean isLeftFocus = true;
    private Activity context;
    private HFXServer server;
    private Toolbar toolbar;
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
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        leftList = findViewById(R.id.rv_left_files);
        rightList = findViewById(R.id.rv_right_files);
        shadowGroupLeft = findViewById(R.id.shadow_group_left);
        shadowGroupRight = findViewById(R.id.shadow_group_right);
        shadowGroupLeft.setVisibility(View.INVISIBLE);
        Toolbar leftSelectToolbar = findViewById(R.id.toolbar_select_left);
        Toolbar rightSelectToolbar = findViewById(R.id.toolbar_select_right);

        server = HFXSService.server;
        if (server == null) {
            Toast.makeText(this, "服务未运行", Toast.LENGTH_SHORT).show();
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
                .setTitle("确认传输")
                .setMessage("是否将选中的 " + selectedItems.size() + " 个文件传输到电脑目录：\n" + rightRVAdapter.getCurrentDir())
                .setPositiveButton("确定", (dialog, which) -> {
                    leftRVAdapter.cancelSelect();
                    sendFilesToRemote(selectedItems, dir, rightRVAdapter.getCurrentDir());
                })
                .setNegativeButton("取消", null)
                .show());
        rightRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle("确认传输")
                .setMessage("是否将选中的 " + selectedItems.size() + " 个文件传输到手机目录：\n" + leftRVAdapter.getCurrentDir())
                .setPositiveButton("确定", (dialog, which) -> {
                    rightRVAdapter.cancelSelect();
                    sendFilesToShelf(selectedItems, leftRVAdapter.getCurrentDir(), dir);
                })
                .setNegativeButton("取消", null)
                .show());
    }

    private void sendFilesToRemote(List<String> files, String localDir, String remoteDir) {
        new SendFilesToRemoteTask(new TransferDialogHandler(context,rightRVAdapter,server), server, files, localDir, remoteDir).execute();
    }

    private void sendFilesToShelf(List<String> selectedItems, String localDir, String remoteDir) {
        new SendFilesToShelfTask(new TransferDialogHandler(context,leftRVAdapter,server), server, selectedItems, localDir, remoteDir).execute();
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
                recyclerView.setAdapter(new BookmarkAdapter(context,builder.show(),leftRVAdapter,false));
            } else {
                builder.setTitle("电脑文件夹书签");
                recyclerView.setAdapter(new BookmarkAdapter(context,builder.show(),rightRVAdapter,true));
            }
            return true;
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
            return true;
        } else if (id == R.id.mkdir){
            View dialogView = View.inflate(context,R.layout.edit_text,null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("新建文件夹" + (isLeftFocus ? "（手机目录）" : "（电脑目录）"))
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)){
                    editText.setError("请输入文件名");
                    return;
                }
                if (Utils.containsIllegalCharacters(input)){
                    editText.setError("文件名不能包含这些字符：<>:\"/\\|?*");
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus){
                    leftRVAdapter.mkdir(leftRVAdapter.getCurrentDir(),input);
                } else {
                    rightRVAdapter.mkdir(rightRVAdapter.getCurrentDir(),input);
                }
            });
        } else if (id == R.id.jump) {
            View dialogView = View.inflate(context,R.layout.edit_text,null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("跳转路径" + (isLeftFocus ? "（手机目录）" : "（电脑目录）"))
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)){
                    editText.setError("请输入路径");
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus){
                    leftRVAdapter.jump(input);
                } else {
                    rightRVAdapter.jump(input);
                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void addBookmark(boolean isRemote,String path) {
        boolean exists;
        ConfigDB configDB = ConfigDB.getInstance(context);
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

    private static class TransferDialogHandler implements SendFilesToRemoteTask.EventHandler,SendFilesToShelfTask.EventHandler{
        Activity context;
        HFXServer server;
        TransferDialog transferDialog;
        FileSelectAdapter adapter;
        public TransferDialogHandler(Activity context, FileSelectAdapter adapter,HFXServer server) {
            this.context = context;
            this.server = server;
            this.adapter = adapter;
            transferDialog = new TransferDialog(context, server.getChannelListINames());
            transferDialog.show();
        }

        @Override
        public void onRequestSendFailed() {
            server.markFailed();
            transferDialog.dismiss();
            new AlertDialog.Builder(context)
                    .setMessage("请求电脑端接收文件失败，连接可能已断开")
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, (dialog, which) -> context.finish())
                    .show();
        }

        @Override
        public void onRequestReceiveFailed() {
            server.markFailed();
            transferDialog.dismiss();
            new AlertDialog.Builder(context)
                    .setMessage("请求电脑端发送文件失败，连接可能已断开")
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, (dialog, which) -> context.finish())
                    .show();
        }

        @Override
        public void onTransferEvent(TransferEvent transferEvent) {
            transferDialog.showEvent(transferEvent);
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> infoList) {
            transferDialog.showSpeeds(infoList);
        }

        @Override
        public void onTransferFailed(String exceptionMessage) {
            server.markFailed();
            Toast.makeText(context, "传输时发生异常，请返回重连！", Toast.LENGTH_LONG).show();
            transferDialog.setCloseBtnEnable(true);
            transferDialog.setButton("退出", v -> context.finish());
        }

        @Override
        public void onTransferOver() {
            transferDialog.setCloseBtnEnable(true);
            adapter.refresh();
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