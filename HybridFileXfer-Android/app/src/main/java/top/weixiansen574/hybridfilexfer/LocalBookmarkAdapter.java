package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.content.DialogInterface;

import java.util.List;

import top.weixiansen574.hybridfilexfer.bean.BookMark;

public class LocalBookmarkAdapter extends BookmarkAdapter{
    private final IIServiceFileSelectAdapter adapter;
    private final List<BookMark> bookMarks;
    private final ConfigDB db;
    public LocalBookmarkAdapter(Context context, DialogInterface dialog,IIServiceFileSelectAdapter adapter, ConfigDB db) {
        super(context,dialog);
        this.db = db;
        this.bookMarks = db.getAllLocalBookmark();
        this.adapter = adapter;
    }

    @Override
    protected boolean onDelete(BookMark bookMark) {
        boolean success = db.removeLocalBookmark(bookMark.id) > 0;
        if (success){
            bookMarks.remove(bookMark);
        }
        return success;
    }

    @Override
    protected BookMark getItem(int position) {
        return bookMarks.get(position);
    }

    @Override
    protected void onItemClick(BookMark bookMark) {
        adapter.cd(bookMark.path);
    }

    @Override
    public int getItemCount() {
        return bookMarks.size();
    }
}
