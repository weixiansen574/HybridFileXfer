package top.weixiansen574.hybridfilexfer.desktop;

import java.util.Locale;

/** The saved preference is independent of the currently resolved system appearance. */
public enum ThemePreference {
    SYSTEM, LIGHT, DARK;

    public static ThemePreference fromString(String value) {
        try { return value == null ? SYSTEM : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return SYSTEM; }
    }
}
