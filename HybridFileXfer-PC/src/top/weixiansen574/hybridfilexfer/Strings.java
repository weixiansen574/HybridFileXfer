package top.weixiansen574.hybridfilexfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

public final class Strings {
    private static final ResourceBundle.Control UTF8 = new ResourceBundle.Control() {
        @Override public ResourceBundle newBundle(String base, Locale locale, String format,
                                                   ClassLoader loader, boolean reload) throws IOException {
            String name = toResourceName(toBundleName(base, locale), "properties");
            try (InputStream stream = loader.getResourceAsStream(name)) {
                if (stream == null) return null;
                return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
        @Override public Locale getFallbackLocale(String baseName, Locale locale) { return null; }
    };
    private static final ResourceBundle MESSAGES = bundleFor(Locale.getDefault());
    /** Regions such as zh_SG use a supported language; unknown languages use the English root. */
    public static ResourceBundle bundleFor(Locale locale) {
        Locale target = locale;
        if ("zh".equals(locale.getLanguage())) {
            String country = locale.getCountry();
            target = country.equals("TW") || country.equals("HK") || country.equals("MO")
                    || "Hant".equals(locale.getScript()) ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
        }
        return ResourceBundle.getBundle("messages", target, UTF8);
    }
    public static String get(String key) { return MESSAGES.getString(key); }
    public static String get(String key, Object... args) { return String.format(get(key), args); }
    public static void printf(String key) { System.out.println(get(key)); }
    public static void printf(String key, Object... args) { System.out.println(get(key, args)); }
}
