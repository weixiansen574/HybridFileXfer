package top.weixiansen574.hybridfilexfer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Strings {

    private static ResourceBundle messages;

    static {
        try {
            // 手动加载 UTF-8 编码的 properties 文件

            messages = new PropertyResourceBundle(
                    new InputStreamReader(
                            Objects.requireNonNull(Strings.class
                                    .getClassLoader()
                                    .getResourceAsStream("messages_" + Locale.getDefault() + ".properties")),
                            StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            System.err.println("Failed to load resource bundle: " + e.getMessage());
        }
    }

    public static String get(String key) {
        return messages.getString(key);
    }

    public static String get(String key, Object... args) {
        try {
            String message = messages.getString(key);
            return String.format(message, args); // 使用占位符替换
        } catch (MissingResourceException e) {
            return "Missing key: " + key;
        }
    }
}
