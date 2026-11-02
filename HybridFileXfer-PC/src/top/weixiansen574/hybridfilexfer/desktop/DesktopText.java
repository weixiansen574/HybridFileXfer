package top.weixiansen574.hybridfilexfer.desktop;

import java.util.Locale;

final class DesktopText {
    private static final boolean CHINESE = "zh".equals(Locale.getDefault().getLanguage());
    private DesktopText() { }
    static String text(String chinese, String english) { return CHINESE ? chinese : english; }
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    static String duration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
