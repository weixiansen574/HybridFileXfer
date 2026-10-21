package top.weixiansen574.hybridfilexfer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Bounded, device-local history. Never stores pairing secrets or file contents. */
public final class LocalHistory {
    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("local_history", Context.MODE_PRIVATE);
    }
    public static List<String> read(Context context, String key) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences(context).getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (Exception ignored) { }
        return result;
    }
    private static void save(Context context, String key, String value, int limit) {
        List<String> values = read(context, key);
        values.remove(value);
        values.add(0, value);
        if (values.size() > limit) values = values.subList(0, limit);
        preferences(context).edit().putString(key, new JSONArray(values).toString()).apply();
    }
    public static void directory(Context context, boolean remote, String path) {
        save(context, remote ? "remote_dirs" : "local_dirs", path, 20);
    }
    public static void transfer(Context context, String result) {
        save(context, "transfers", DateFormat.getDateTimeInstance().format(new Date()) + "\n" + result, 50);
    }
    public static void clear(Context context) { preferences(context).edit().clear().apply(); }
}
