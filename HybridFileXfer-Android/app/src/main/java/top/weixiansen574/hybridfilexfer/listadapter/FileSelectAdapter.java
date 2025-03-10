package top.weixiansen574.hybridfilexfer.listadapter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.RemoteException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.l4digital.fastscroll.FastScroller;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.tasks.DeleteFilesTask;
import top.weixiansen574.hybridfilexfer.tasks.ListFilesTask;

public abstract class FileSelectAdapter extends RecyclerView.Adapter<FileSelectAdapter.ViewHolder> implements FastScroller.SectionIndexer {
    Activity context;
    LayoutInflater layoutInflater;
    protected HFXServer server;
    List<RemoteFile> files = new ArrayList<>();
    HashSet<RemoteFile> selectedItems = new HashSet<>();
    public View loadingView;
    public RecyclerView recyclerView;
    public LinearLayoutManager linearLayoutManager;
    public Toolbar fileSelectToolbar;
    View.OnTouchListener onTouchListener;
    private int lastSelectedCount = 0;
    private OnConfirmFileSelectionListener onConfirmFileSelectionListener;
    private LinkedList<DirPosition> positions = new LinkedList<>();
    private final int fileSystem;

    public FileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        this.context = context;
        this.server = server;
        this.loadingView = loadingView;
        this.recyclerView = recyclerView;
        this.linearLayoutManager = linearLayoutManager;
        this.onTouchListener = onTouchListener;
        this.fileSelectToolbar = fileSelectToolbar;

        this.fileSystem = getFileSystem(server);

        layoutInflater = LayoutInflater.from(context);
        jump(getDefaultDir(server));
        fileSelectToolbar.getMenu()
                .findItem(R.id.select_all)
                .setOnMenuItemClickListener(item -> {
                    selectAll();
                    return true;
                });
        fileSelectToolbar.getMenu()
                .findItem(R.id.delete)
                .setOnMenuItemClickListener(item -> {
                    List<String> files = new ArrayList<>(selectedItems.size());
                    for (RemoteFile file : selectedItems) {
                        files.add(file.getPath());
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(context.getString(R.string.que_ren_shan_chu_, selectedItems.size()));
                    if (selectedItems.size() < 4) {
                        for (String file : files) {
                            sb.append(file).append("\n");
                        }
                    } else {
                        for (int i = 0; i < 3; i++) {
                            String file = files.get(i);
                            sb.append(file).append("\n");
                        }
                        sb.append("……");
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.que_ren_shan_chu)
                            .setMessage(sb.toString())
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.ok, (dialog, which) -> {
                                cancelSelect();

                                ProgressDialog progressDialog = new ProgressDialog(context);
                                progressDialog.setCancelable(false);
                                progressDialog.setProgress(0);
                                progressDialog.setTitle(context.getString(R.string.shan_chu_zhong__));
                                progressDialog.setMessage("");
                                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                                progressDialog.setMax(files.size());
                                progressDialog.show();

                                new DeleteFilesTask(new DeleteFilesTask.EventHandler() {
                                    @Override
                                    public void onDeleting(int index, String file) {
                                        progressDialog.setProgress(index);
                                        progressDialog.setMessage(file);
                                    }

                                    @Override
                                    public void onFailed(int index, String file) {
                                        progressDialog.dismiss();
                                        new AlertDialog.Builder(context)
                                                .setTitle(R.string.shan_chu_wen_jian_shi_bai)
                                                .setMessage(context.getString(R.string.delete_failed_message, index, file))
                                                .setPositiveButton(R.string.ok, null)
                                                .show();
                                    }

                                    @Override
                                    public void onSuccess() {
                                        progressDialog.dismiss();
                                        Toast.makeText(context, R.string.file_delete_success, Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onComplete() {
                                        refresh();
                                    }
                                }, this, files).execute();
                            }).show();
                    return true;
                });
        fileSelectToolbar.setNavigationOnClickListener(v -> cancelSelect());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(layoutInflater.inflate(R.layout.item_file, parent, false), onTouchListener);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RemoteFile item = files.get(position);
        View itemView = holder.itemView;
        holder.fileName.setText(item.getName());
        if (position == 0) {//..文件夹
            itemView.setBackground(null);
            holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.folder));
            itemView.setOnClickListener(v -> {
                if (!isSelectMode()) {
                    back();
                } else {
                    Toast.makeText(context, R.string.qing_xian_qu_xiao_xuan_ze, Toast.LENGTH_SHORT).show();
                }
            });
            itemView.setOnLongClickListener(null);
            holder.fileSize.setText("");
            holder.dateTime.setText("");
            return;
        }

        holder.dateTime.setText(Utils.formatDateTime(item.lastModified()));
        if (item.isDirectory()) {
            holder.fileSize.setText("");
        } else {
            holder.fileSize.setText(Utils.formatFileSize(item.getSize()));
        }
        if (selectedItems.contains(item)) {
            itemView.setBackgroundColor(context.getColor(R.color.blue_background_light));
            holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.baseline_check_circle_24));
        } else {
            itemView.setBackground(null);
            if (item.isDirectory()) {
                holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.folder));
            } else {
                holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.file));
            }
        }

        itemView.setOnLongClickListener(v -> {
            if (!isSelectMode()) {
                if (!selectedItems.contains(item)) {
                    addSelectItem(item);
                } else {
                    removeSelectItem(item);
                }
                notifyItemChanged(holder.getAdapterPosition());
            } else {
                if (selectedItems.contains(item)) {
                    onConfirmFileSelection(selectedItems);
                }
            }
            return true;
        });

        itemView.setOnClickListener(v -> {
            if (isSelectMode()) {
                if (!selectedItems.contains(item)) {
                    addSelectItem(item);
                } else {
                    removeSelectItem(item);
                }
                notifyItemChanged(holder.getAdapterPosition());
            } else if (item.isDirectory()) {
                cd(item.getPath());
            }
        });
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public boolean isSelectMode() {
        return !selectedItems.isEmpty();
    }

    private void addSelectItem(RemoteFile item) {
        selectedItems.add(item);
        updateSelectedCount();
    }

    private void removeSelectItem(RemoteFile item) {
        selectedItems.remove(item);
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        int size = selectedItems.size();
        fileSelectToolbar.setTitle(context.getString(R.string.selected_items_count, size));

        if (selectedItems.isEmpty()) {
            fileSelectToolbar.setVisibility(View.INVISIBLE);
        } else if (lastSelectedCount == 0) {
            fileSelectToolbar.setVisibility(View.VISIBLE);
        }
        lastSelectedCount = size;
    }

    protected void loading() {
        // 首先将视图的透明度设为 0（隐藏状态）
        loadingView.setAlpha(0f);
        // 然后将视图设置为可见
        loadingView.setVisibility(View.VISIBLE);

        // 使用 ViewPropertyAnimator 渐渐显示
        loadingView.animate()
                .alpha(1f)  // 最终透明度为 1（完全显示）
                .setDuration(1000);  // 动画持续时间，单位为毫秒

        recyclerView.setVisibility(View.INVISIBLE);
    }

    protected void cancelLoading() {
        loadingView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        recyclerView.scheduleLayoutAnimation();
    }

    @SuppressLint("NotifyDataSetChanged")
    protected void changeFiles(List<RemoteFile> files) {
        loadingView.setVisibility(View.GONE);
        this.files = files;
        notifyDataSetChanged();
        recyclerView.setVisibility(View.VISIBLE);
        recyclerView.scheduleLayoutAnimation();
    }

    public void cd(String dir) {
        loading();
        DirPosition currentPosition = positions.get(positions.size() - 1);
        //String dir = currentPosition.dir + name + "/";
        //获取当前可见的第一个元素的位置
        currentPosition.position = linearLayoutManager.findFirstVisibleItemPosition();

        System.out.println("cd " + dir);
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    DirPosition dirPosition = new DirPosition(new Directory(dir, fileSystem), 0);
                    positions.add(dirPosition);
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.jin_ru_mu_lu_shi_bai, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public void jump(String dir) {
        loading();
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    positions = createNewDirPositions(new Directory(dir, fileSystem));
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.tiao_zhuan_shi_bai, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public void refresh() {
        loading();
        listFilesASync(getCurrentDir(), files -> {
            if (files != null) {
                changeFiles(files);
            } else {
                cancelLoading();
                Toast.makeText(context, R.string.shua_xin_shi_bai, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void back() {
        if (positions.size() < 2) {
            Toast.makeText(context, R.string.yi_jing_dao_gen_mu_lu_le, Toast.LENGTH_SHORT).show();
            return;
        }
        loading();
        DirPosition dirPosition = positions.get(positions.size() - 2);
        String dir = dirPosition.dir.path;
        System.out.println("back " + dir);
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    if (files.isEmpty()) {
                        cancelLoading();
                        Toast.makeText(context, R.string.fu_mu_lu_wen_jian_shu_wei_kong, Toast.LENGTH_SHORT).show();
                    } else {
                        positions.removeLast();
                        System.out.println(positions);
                        changeFiles(files);
                        linearLayoutManager.scrollToPositionWithOffset(dirPosition.position, 0);
                    }
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.wu_quan_fang_wen_shang_ceng_mu_lu, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract void handleListException(Throwable th);

    protected abstract String getDefaultDir(HFXServer server);

    protected abstract int getFileSystem(HFXServer server);

    //protected abstract void onDeleteFiles(List<String> files);
    public abstract boolean deleteFile(String file) throws RemoteException, IOException;

    public abstract void mkdir(String parent, String child);

    private void listFilesASync(String path, ListFilesTask.CallBack callBack) {
        new ListFilesTask(callBack, path, this).execute();
    }


    public void setOnToTransferListener(OnConfirmFileSelectionListener onConfirmFileSelectionListener) {
        this.onConfirmFileSelectionListener = onConfirmFileSelectionListener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void cancelSelect() {
        selectedItems.clear();
        updateSelectedCount();
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAll() {
        selectedItems.clear();
        selectedItems.addAll(files);
        selectedItems.remove(files.get(0));//排除”..“文件夹
        updateSelectedCount();
        notifyDataSetChanged();
    }

    public String getCurrentDir() {
        return getCurrentDirectory().path;
    }

    public Directory getCurrentDirectory(){
        if (positions.isEmpty()){
            return new Directory("/",fileSystem);
        }
        return positions.get(positions.size() - 1).dir;
    }

    public interface OnConfirmFileSelectionListener {
        void onConfirmFileSelection(List<RemoteFile> selectedItems, Directory dir);
    }

    protected void onConfirmFileSelection(HashSet<RemoteFile> selectedItems) {
        List<RemoteFile> remoteFiles = new ArrayList<>(selectedItems);
        if (onConfirmFileSelectionListener != null) {
            onConfirmFileSelectionListener.onConfirmFileSelection(remoteFiles, getCurrentDirectory());
        }
    }


    private LinkedList<DirPosition> createNewDirPositions(@NotNull Directory directory) {
        LinkedList<DirPosition> positions = new LinkedList<>();
        while (directory != null) {
            positions.add(new DirPosition(directory, 0));
            directory = directory.parent();
        }
        Collections.reverse(positions);
        System.out.println(positions);
        return positions;
    }

    private static class DirPosition {
        public Directory dir;
        public int position;

        public DirPosition(Directory dir, int position) {
            this.dir = dir;
            this.position = position;
        }

        @Override
        public String toString() {
            return "DirPosition{" +
                    "dir='" + dir + '\'' +
                    ", position=" + position +
                    '}';
        }
    }

    @Override
    public CharSequence getSectionText(int position) {
        return String.valueOf(files.get(position).getName().charAt(0));
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName, dateTime, fileSize;

        public ViewHolder(@NonNull View itemView, View.OnTouchListener onTouchListener) {
            super(itemView);
            itemView.setOnTouchListener(onTouchListener);
            fileIcon = itemView.findViewById(R.id.file_icon);
            fileName = itemView.findViewById(R.id.file_name);
            dateTime = itemView.findViewById(R.id.date_time);
            fileSize = itemView.findViewById(R.id.file_size);
        }
    }
}
