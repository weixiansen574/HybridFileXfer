package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import top.weixiansen574.hybridfilexfer.bean.BookMark;

public abstract class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.MyViewHolder> {
    public Context context;
    public DialogInterface dialog;

    public BookmarkAdapter(Context context, DialogInterface dialog) {
        this.context = context;
        this.dialog = dialog;
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new MyViewHolder(LayoutInflater.from(context).inflate(R.layout.item_bookmark, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull MyViewHolder holder, int position) {
        BookMark item = getItem(position);
        holder.fileName.setText(item.path);
        holder.itemView.setOnClickListener(v -> {
            onItemClick(item);
            dialog.dismiss();
        });
        holder.itemView.setOnLongClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要删除书签：" + item.path + " 吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (dialog, which) -> {
                        if (onDelete(item)) {
                            Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show();
                            notifyItemRemoved(holder.getAdapterPosition());
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });
    }

    protected abstract boolean onDelete(BookMark bookMark);

    protected abstract BookMark getItem(int position);
    protected abstract void onItemClick(BookMark bookMark);

    public static class MyViewHolder extends RecyclerView.ViewHolder {
        TextView fileName;
        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
            fileName = itemView.findViewById(R.id.file_name);
        }
    }
}
