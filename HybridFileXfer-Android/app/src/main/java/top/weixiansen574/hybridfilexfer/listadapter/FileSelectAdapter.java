package top.weixiansen574.hybridfilexfer.listadapter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.Utils;
import top.weixiansen574.hybridfilexfer.core.HFXServer;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.tasks.DeleteFilesTask;
import top.weixiansen574.hybridfilexfer.tasks.ListFilesTask;

public abstract class FileSelectAdapter extends RecyclerView.Adapter<FileSelectAdapter.ViewHolder> implements FastScroller.SectionIndexer  {
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

    public FileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        this.context = context;
        this.server = server;
        this.loadingView = loadingView;
        this.recyclerView = recyclerView;
        this.linearLayoutManager = linearLayoutManager;
        this.onTouchListener = onTouchListener;
        this.fileSelectToolbar = fileSelectToolbar;

        layoutInflater = LayoutInflater.from(context);
        jump(getDefaultDir());
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
                    sb.append("确认删除这 ").append(files.size()).append(" 个文件吗？\n");
                    if (selectedItems.size() < 4){
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
                            .setTitle("确认删除")
                            .setMessage(sb.toString())
                            .setNegativeButton(R.string.cancel,null)
                            .setPositiveButton(R.string.ok, (dialog, which) -> {
                                cancelSelect();

                                ProgressDialog progressDialog = new ProgressDialog(context);
                                progressDialog.setProgress(0);
                                progressDialog.setTitle("删除中……");
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
                                                .setTitle("删除文件失败")
                                                .setMessage("第"+index+"个文件：\n"+file+"\n删除失败！")
                                                .setPositiveButton(R.string.ok,null)
                                                .show();
                                    }

                                    @Override
                                    public void onSuccess() {
                                        progressDialog.dismiss();
                                        Toast.makeText(context, "文件删除成功！", Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onComplete() {
                                        refresh();
                                    }
                                },this,files).execute();
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
                if (!isSelectMode()){
                    back();
                } else {
                    Toast.makeText(context, "请先取消选择！", Toast.LENGTH_SHORT).show();
                }
            });
            itemView.setOnLongClickListener(null);
            holder.fileSize.setText("");
            holder.dateTime.setText("");
            return;
        }

        holder.dateTime.setText(Utils.formatDateTime(item.getLastModified()));
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
        fileSelectToolbar.setTitle("已选择 " + size + " 项");

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
                    DirPosition dirPosition = new DirPosition(dir, 0);
                    positions.add(dirPosition);
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, "进入目录失败", Toast.LENGTH_SHORT).show();
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
                    positions = createNewDirPositions(dir);
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, "跳转失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public void refresh(){
        loading();
        listFilesASync(getCurrentDir(), files -> {
            if (files != null) {
                changeFiles(files);
            } else {
                cancelLoading();
                Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void back() {
        if (positions.size() < 2) {
            Toast.makeText(context, "已经到根目录了", Toast.LENGTH_SHORT).show();
            return;
        }
        loading();
        DirPosition dirPosition = positions.get(positions.size() - 2);
        String dir = dirPosition.dir;
        System.out.println("back " + dir);
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    if (files.isEmpty()) {
                        cancelLoading();
                        Toast.makeText(context, "父目录文件数为空，为避免回不来不执行切换！", Toast.LENGTH_SHORT).show();
                    } else {
                        positions.removeLast();
                        System.out.println(positions);
                        changeFiles(files);
                        linearLayoutManager.scrollToPositionWithOffset(dirPosition.position, 0);
                    }
                } else {
                    cancelLoading();
                    Toast.makeText(context, "无权访问上层目录", Toast.LENGTH_SHORT).show();
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

    protected abstract String getDefaultDir();
    //protected abstract void onDeleteFiles(List<String> files);
    public abstract boolean deleteFile(String file);

    public abstract void mkdir(String parent,String child);

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
        return positions.get(positions.size() - 1).dir;
    }

    public interface OnConfirmFileSelectionListener {
        void onConfirmFileSelection(List<String> selectedItems, String dir);
    }

    protected void onConfirmFileSelection(HashSet<RemoteFile> selectedItems) {
        List<String> files = new ArrayList<>();
        for (RemoteFile item : selectedItems) {
            files.add(item.getPath());
        }
        if (onConfirmFileSelectionListener != null) {
            onConfirmFileSelectionListener.onConfirmFileSelection(files, getCurrentDir());
        }
    }


    private LinkedList<DirPosition> createNewDirPositions(String dir) {
        LinkedList<DirPosition> positions = new LinkedList<>();
        //dir = Utils.replaceBackslashToSlash(dir);
        String parent = dir;
        do {
            /*if (!parent.endsWith("/")){
                parent = parent + "/";
            }*/
            positions.add(new DirPosition(parent, 0));
            parent = Utils.getParentByPath(parent);
        } while (parent != null);
        Collections.reverse(positions);
        System.out.println(positions);
        return positions;
    }

    private static class DirPosition {
        public String dir;
        public int position;

        public DirPosition(String dir, int position) {
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
