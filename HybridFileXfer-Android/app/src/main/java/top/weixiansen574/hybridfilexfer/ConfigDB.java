package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.bean.BookMark;

@SuppressLint("Range")
public class ConfigDB extends SQLiteOpenHelper {
    public static final int VERSION = 1;

    public ConfigDB(@Nullable Context context) {
        super(context, "config", null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    NOT NULL\n" +
                "                 UNIQUE\n" +
                ");");
        db.execSQL("CREATE TABLE remote_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    UNIQUE\n" +
                "                 NOT NULL\n" +
                ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    public long addLocalBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("local_dir_bookmarks", null, cv);

    }

    public long addRemoteBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("remote_dir_bookmarks", null, cv);
    }

    public boolean checkLocalBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM local_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    public boolean checkRemoteBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM remote_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }


    public long removeLocalBookmark(int id) {
        return getWritableDatabase()
                .delete("local_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public long removeRemoteBookmark(int id) {
        return getWritableDatabase()
                .delete("remote_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }


    public List<BookMark> getAllLocalBookmark() {
        return getAllBookmarks("local_dir_bookmarks");
    }

    public List<BookMark> getAllRemoteBookmark() {
        return getAllBookmarks("remote_dir_bookmarks");
    }

    private List<BookMark> getAllBookmarks(String tableName){
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM "+tableName, null);
        List<BookMark> bookmarks = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            bookmarks.add(
                    new BookMark(
                            cursor.getInt(cursor.getColumnIndex("id")),
                            cursor.getString(cursor.getColumnIndex("path"))
                    ));
        }
        cursor.close();
        return bookmarks;
    }
}
