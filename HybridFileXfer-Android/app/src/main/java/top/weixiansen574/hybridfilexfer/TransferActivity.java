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

public class TransferActivity extends AppCompatActivity implements TransferSession.Listener {
    private TransferSession transferSession;
    private TransferDialog transferDialog;
    private boolean refreshedAfterTransfer;
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

        leftRVAdapter.setOnToTransferListener((files, dir) -> confirmTransfer(true, files, dir, rightRVAdapter.getCurrentDirectory()));
        rightRVAdapter.setOnToTransferListener((files, dir) -> confirmTransfer(false, files, leftRVAdapter.getCurrentDirectory(), dir));
        findViewById(R.id.transfer_to_remote).setOnClickListener(v -> leftRVAdapter.confirmSelection());
        findViewById(R.id.transfer_to_local).setOnClickListener(v -> rightRVAdapter.confirmSelection());
        TransferSession existing = TransferSession.current();
        if (existing != null && !existing.acknowledged) {
            transferSession = existing;
            transferSession.attach(this);
        }
    }

    private void confirmTransfer(boolean upload, List<RemoteFile> files, Directory local, Directory remote) {
        if (!leftRVAdapter.isDirectoryReady() || !rightRVAdapter.isDirectoryReady()) {
            Toast.makeText(this, R.string.wait_for_folders, Toast.LENGTH_LONG).show();
            return;
        }
        if (server != HFXServer.instance) {
            Toast.makeText(this, R.string.session_changed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (TransferSession.current() != null && TransferSession.current().running) {
            Toast.makeText(this, R.string.transfer_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        android.widget.TextView destination = new android.widget.TextView(this);
        destination.setText(getString(upload ? R.string.chuan_shu_dao_dian_nao_mu_lu : R.string.chuan_shu_dao_shou_ji_mu_lu,
                files.size(), upload ? remote.path : local.path));
        form.addView(destination);
        android.widget.Spinner policy = new android.widget.Spinner(this);
        policy.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.conflict_policies)));
        form.addView(policy);
        android.widget.CheckBox verify = new android.widget.CheckBox(this);
        verify.setText(R.string.verify_sha256); verify.setChecked(false); form.addView(verify);
        android.widget.TextView verificationHint = new android.widget.TextView(this);
        verificationHint.setText(R.string.verify_sha256_description);
        form.addView(verificationHint);
        android.widget.CheckBox resume = new android.widget.CheckBox(this);
        resume.setText(R.string.resume_verified); resume.setChecked(true); form.addView(resume);
        android.widget.CheckBox incremental = new android.widget.CheckBox(this);
        incremental.setText(R.string.incremental_transfer); form.addView(incremental);
        android.widget.CheckBox prescan = new android.widget.CheckBox(this);
        prescan.setText(R.string.prescan_transfer); form.addView(prescan);
        new AlertDialog.Builder(this).setTitle(R.string.que_ren_chuan_shu).setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (server != HFXServer.instance) {
                        Toast.makeText(this, R.string.session_changed, Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    top.weixiansen574.hybridfilexfer.core.TransferOptions options = new top.weixiansen574.hybridfilexfer.core.TransferOptions();
                    top.weixiansen574.hybridfilexfer.core.TransferOptions.ConflictPolicy[] policies = {
                        top.weixiansen574.hybridfilexfer.core.TransferOptions.ConflictPolicy.KEEP_BOTH,
                        top.weixiansen574.hybridfilexfer.core.TransferOptions.ConflictPolicy.SKIP,
                        top.weixiansen574.hybridfilexfer.core.TransferOptions.ConflictPolicy.OVERWRITE};
                    options.conflictPolicy = policies[policy.getSelectedItemPosition()];
                    options.verifySha256 = verify.isChecked();
                    options.resume = resume.isChecked();
                    options.incremental = incremental.isChecked();
                    options.prescan = prescan.isChecked();
                    leftRVAdapter.cancelSelect(); rightRVAdapter.cancelSelect();
                    refreshedAfterTransfer = false;
                    transferSession = TransferSession.start(this, server, upload, files, local, remote, options);
                    transferSession.attach(this);
                }).show();
    }

    @Override public void onTransferChanged() {
        if (isFinishing() || isDestroyed() || transferSession == null) return;
        if (transferDialog == null) {
            transferDialog = new TransferDialog(this, transferSession.upload, transferSession.channelNames);
            transferDialog.show();
            transferDialog.setOnCancel(transferSession::cancel);
            transferDialog.getDialog().setOnDismissListener(dialog -> {
                if (!transferSession.running) transferSession.acknowledged = true;
                transferDialog = null;
            });
        }
        for (java.util.Map.Entry<String, String> event : transferSession.events.entrySet()) {
            transferDialog.showEvent(event.getKey(), event.getValue());
        }
        if (transferSession.running) {
            transferDialog.showSpeeds(transferSession.speeds);
            if (transferSession.verification.active()) {
                transferDialog.setOnCancel(transferSession::stopVerification);
                transferDialog.showVerification(transferSession.verification.progress(),
                        transferSession.verification.stopping());
            } else {
                transferDialog.showProgress(transferSession.progress, transferSession.status);
            }
        } else {
            transferDialog.complete(transferSession.upload, transferSession.traffic, transferSession.elapsed);
            transferDialog.showResult(transferSession.resultText());
            if (HFXServer.instance != server) {
                transferDialog.setButton(getString(R.string.return_to_main), v -> finish());
            }
            if (!refreshedAfterTransfer) {
                refreshedAfterTransfer = true;
                if (HFXServer.instance == server) {
                    leftRVAdapter.refresh(); rightRVAdapter.refresh();
                }
            }
        }
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
        if (id == R.id.transfer_history) {
            java.util.List<String> entries = LocalHistory.read(this, "transfers");
            new AlertDialog.Builder(this).setTitle(R.string.transfer_history)
                    .setMessage(entries.isEmpty() ? getString(R.string.history_empty) : android.text.TextUtils.join("\n\n", entries))
                    .setPositiveButton(R.string.close, null)
                    .setNeutralButton(R.string.clear_history, (d, w) -> LocalHistory.clear(this)).show();
            return true;
        }
        if (id == R.id.recent_directories || id == R.id.storage_locations) {
            showLocations(id == R.id.storage_locations);
            return true;
        }
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

    private void showLocations(boolean storage) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (storage && isLeftFocus) {
            paths.add(android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
            for (java.io.File directory : getExternalFilesDirs(null)) {
                if (directory == null) continue;
                String path = directory.getAbsolutePath();
                int marker = path.indexOf("/Android/data/");
                if (marker > 0 && !paths.contains(path.substring(0, marker))) paths.add(path.substring(0, marker));
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.os.storage.StorageManager manager = getSystemService(android.os.storage.StorageManager.class);
                for (android.os.storage.StorageVolume volume : manager.getStorageVolumes()) {
                    java.io.File directory = volume.getDirectory();
                    if (directory != null && !paths.contains(directory.getAbsolutePath())) paths.add(directory.getAbsolutePath());
                }
            }
            if (Config.getInstance(this).getMode() != Config.MODE_NORMAL) paths.add("/");
        } else if (storage) {
            paths.add(server.getRemoteHomeDir());
            if (!paths.contains("/")) paths.add("/");
        } else {
            paths.addAll(LocalHistory.read(this, isLeftFocus ? "local_dirs" : "remote_dirs"));
        }
        if (paths.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        FileSelectAdapter target = isLeftFocus ? leftRVAdapter : rightRVAdapter;
        new AlertDialog.Builder(this).setTitle(storage ? R.string.storage_locations : R.string.recent_directories)
                .setItems(paths.toArray(new String[0]), (dialog, which) -> target.jump(paths.get(which)))
                .setNegativeButton(R.string.cancel, null).show();
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

    @Override protected void onStart() {
        super.onStart();
        if (transferSession != null && !transferSession.acknowledged) transferSession.attach(this);
    }
    @Override protected void onStop() {
        if (transferSession != null) transferSession.detach(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (transferSession != null) transferSession.detach(this);
        if (transferDialog != null) {
            transferDialog.getDialog().setOnDismissListener(null);
            transferDialog.dismiss();
        }
        super.onDestroy();
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
