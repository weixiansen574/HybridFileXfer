package top.weixiansen574.hybridfilexfer.desktop;

import java.util.Locale;

/** 桌面外观主题偏好选项（跟随系统 / 浅色 / 深色）。 */
public enum ThemePreference {
    SYSTEM, LIGHT, DARK;

    public static ThemePreference fromString(String value) {
        try { return value == null ? SYSTEM : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return SYSTEM; }
    }
}
