package top.weixiansen574.hybridfilexfer.listadapter;

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

import java.util.List;

import top.weixiansen574.hybridfilexfer.ConfigDB;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.bean.BookMark;


public class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.MyViewHolder> {
    protected Context context;
    protected DialogInterface dialog;
    protected FileSelectAdapter adapter;
    protected List<BookMark> bookMarks;
    protected final ConfigDB db;
    protected final boolean isRemote;

    public BookmarkAdapter(Context context, DialogInterface dialog, FileSelectAdapter adapter,boolean isRemote) {
        this.context = context;
        this.dialog = dialog;
        this.adapter = adapter;
        this.db = ConfigDB.getInstance(context);
        this.isRemote = isRemote;
        if (isRemote){
            bookMarks = db.getAllRemoteBookmark();
        } else {
            bookMarks = db.getAllLocalBookmark();
        }
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new MyViewHolder(LayoutInflater.from(context).inflate(R.layout.item_bookmark, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull MyViewHolder holder, int position) {
        BookMark item = bookMarks.get(position);
        holder.fileName.setText(item.path);
        holder.itemView.setOnClickListener(v -> {
            adapter.jump(item.path);
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
                            bookMarks.remove(item);
                            notifyItemRemoved(holder.getAdapterPosition());
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return bookMarks.size();
    }

    private boolean onDelete(BookMark bookMark){
        if (isRemote){
            return db.removeRemoteBookmark(bookMark.id) > 0;
        } else {
            return db.removeLocalBookmark(bookMark.id) > 0;
        }
    }

    public static class MyViewHolder extends RecyclerView.ViewHolder {
        TextView fileName;
        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
            fileName = itemView.findViewById(R.id.file_name);
        }
    }
}
