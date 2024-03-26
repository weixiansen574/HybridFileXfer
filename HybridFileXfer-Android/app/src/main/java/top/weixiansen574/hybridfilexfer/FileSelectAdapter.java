package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;


public abstract class FileSelectAdapter<T> extends RecyclerView.Adapter<FileSelectAdapter.ViewHolder> {
    public final Toolbar fileSelectToolbar;
    private SelectModeListener selectModeListener;
    private final View.OnTouchListener onTouchListener;
    protected HashSet<T> selectedItems;
    private OnConfirmFileSelectionListener onConfirmFileSelectionListener;
    protected Activity context;
    private int lastSelectedCount = 0;
    private final FrameLayout frameLayout;
    private final RecyclerView recyclerView;
    private final View loadingView;

    public FileSelectAdapter(Activity context, View.OnTouchListener onTouchListener,Toolbar fileSelectToolbar,FrameLayout frameLayout,RecyclerView recyclerView){
        this.fileSelectToolbar = fileSelectToolbar;
        this.context = context;
        this.onTouchListener = onTouchListener;
        this.frameLayout = frameLayout;
        this.recyclerView = recyclerView;

        selectedItems = new HashSet<>();
        loadingView = View.inflate(context,R.layout.loading_files,null);
        fileSelectToolbar.getMenu()
                .findItem(R.id.select_all)
                .setOnMenuItemClickListener(item -> {
                    selectAll();
                    return true;
                });
        fileSelectToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelSelect();
            }
        });
    }

    public void setSelectModeListener(SelectModeListener selectModeListener) {
        this.selectModeListener = selectModeListener;
    }

    public void setOnToTransferListener(OnConfirmFileSelectionListener onConfirmFileSelectionListener) {
        this.onConfirmFileSelectionListener = onConfirmFileSelectionListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_file, parent, false),onTouchListener);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        T item = getItem(position);
        View itemView = holder.itemView;
        holder.fileName.setText(getFileName(item));
        holder.dateTime.setText(Utils.formatDateTime(getFileDate(item)));
        if (isDir(item)){
            holder.fileSize.setText("");
        } else {
            holder.fileSize.setText(Utils.formatFileSize(getFileSize(item)));
        }
        if (selectedItems.contains(item)) {
            itemView.setBackgroundColor(context.getColor(R.color.blue_background_light));
            holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.baseline_check_circle_24));
        } else {
            itemView.setBackgroundColor(context.getColor(R.color.background));
            if (isDir(item)) {
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
            } else if (isDir(item)){
                cd(getPath(item));
            }
        });
    }




    private void addSelectItem(T item) {
        selectedItems.add(item);
        updateSelectedCount();
    }

    private void removeSelectItem(T item) {
        selectedItems.remove(item);
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        int size = selectedItems.size();
        fileSelectToolbar.setTitle("已选择 " + size + " 项");
        if (selectModeListener != null) {
            if (selectedItems.isEmpty()) {
                selectModeListener.onSelectModeChanged(false);
            } else if (lastSelectedCount == 0){
                selectModeListener.onSelectModeChanged(true);
            }
        }
        lastSelectedCount = size;
    }


    protected abstract String getFileName(T item);
    protected abstract String getPath(T item);

    protected abstract boolean isDir(T item);

    protected abstract T getItem(int position);
    protected abstract long getFileDate(T item);
    protected abstract long getFileSize(T item);

    protected abstract List<T> getAllItems();
    protected abstract void cd(String path);

    protected void onConfirmFileSelection(HashSet<T> selectedItems){
        List<String> files = new ArrayList<>();
        for (T item : selectedItems) {
            files.add(getPath(item));
        }
        onConfirmFileSelectionListener.onConfirmFileSelection(files,getCurrentDir());
    };
    public abstract String getCurrentDir();

    public abstract void cdParent();

    public boolean isSelectMode() {
        return !selectedItems.isEmpty();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAll() {
        selectedItems.clear();
        selectedItems.addAll(getAllItems());
        updateSelectedCount();
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void cancelSelect() {
        selectedItems.clear();
        updateSelectedCount();
        notifyDataSetChanged();
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName,dateTime,fileSize;

        public ViewHolder(@NonNull View itemView, View.OnTouchListener onTouchListener) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_icon);
            fileName = itemView.findViewById(R.id.file_name);
            dateTime = itemView.findViewById(R.id.date_time);
            fileSize = itemView.findViewById(R.id.file_size);
            itemView.setOnTouchListener(onTouchListener);
        }
    }

    interface SelectModeListener {
        void onSelectModeChanged(boolean isSelectMode);
    }

    interface OnConfirmFileSelectionListener {
        void onConfirmFileSelection(List<String> selectedItems, String dir);
    }

    public void enterLoadingState(){
        frameLayout.removeView(recyclerView);
        frameLayout.addView(loadingView);
    }

    public void exitLoadingState(){
        frameLayout.removeView(loadingView);
        frameLayout.addView(recyclerView);
    }
}
