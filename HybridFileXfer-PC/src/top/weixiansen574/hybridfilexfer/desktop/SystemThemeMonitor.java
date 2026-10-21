package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Bounded, background OS preference polling. Unsupported desktops retain their
 * last known appearance (initially light); explicit user choices always win.
 */
public final class SystemThemeMonitor implements AutoCloseable {
    private static volatile Boolean systemDark;
    private final Runnable onThemeChanged;
    private final Supplier<Boolean> detector;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    public SystemThemeMonitor(Runnable onThemeChanged) {
        this(onThemeChanged, SystemThemeMonitor::detectDarkMode);
    }

    SystemThemeMonitor(Runnable onThemeChanged, Supplier<Boolean> detector) {
        this.onThemeChanged = onThemeChanged;
        this.detector = detector;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hfx-system-theme");
            thread.setDaemon(true);
            return thread;
        });
    }

    static boolean currentSystemDark() {
        Boolean known = systemDark;
        if (known == null) {
            known = detectDarkMode();
            systemDark = known == null ? Boolean.FALSE : known;
        }
        return Boolean.TRUE.equals(systemDark);
    }

    public void start() {
        if (!closed.get() && started.compareAndSet(false, true))
            worker.scheduleWithFixedDelay(this::poll, 0, 3, TimeUnit.SECONDS);
    }

    private void poll() {
        if (closed.get() || DesktopTheme.getPreference() != ThemePreference.SYSTEM) return;
        try {
            Boolean detected = detector.get();
            if (detected == null || closed.get()) return;
            systemDark = detected;
            if (DesktopTheme.isDark() == detected) return;
            SwingUtilities.invokeLater(() -> {
                if (closed.get() || DesktopTheme.getPreference() != ThemePreference.SYSTEM ||
                        DesktopTheme.isDark() == Boolean.TRUE.equals(systemDark)) return;
                DesktopTheme.configure(ThemePreference.SYSTEM);
                onThemeChanged.run();
            });
        } catch (RuntimeException ignored) {
            // Optional OS preferences must never take down an otherwise usable window.
        }
    }

    @Override public void close() {
        closed.set(true);
        worker.shutdownNow();
    }

    static Boolean detectDarkMode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows"))
            return parseWindows(query("reg.exe", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"));
        if (os.contains("mac")) {
            String value = query("defaults", "read", "-g", "AppleInterfaceStyle");
            if (value == null) return null;
            return value.trim().equalsIgnoreCase("dark");
        }
        boolean kdeDesktop = String.valueOf(System.getenv("XDG_CURRENT_DESKTOP"))
                .toLowerCase(Locale.ROOT).contains("kde");
        if (kdeDesktop) {
            Boolean kde = detectKde();
            if (kde != null) return kde;
        }
        String value = query("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        Boolean result = parseColorScheme(value);
        if (result != null) return result;
        String gtk = query("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
        if (gtk != null && !gtk.trim().isEmpty()) return gtk.toLowerCase(Locale.ROOT).contains("dark");
        return kdeDesktop ? null : detectKde();
    }

    private static Boolean detectKde() {
        String kde = query("kreadconfig6", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme");
        if (kde == null || kde.trim().isEmpty())
            kde = query("kreadconfig5", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme");
        return kde == null || kde.trim().isEmpty() ? null : kde.toLowerCase(Locale.ROOT).contains("dark");
    }

    static Boolean parseWindows(String output) {
        if (output == null) return null;
        for (String line : output.split("\\R")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length >= 3 && tokens[0].equalsIgnoreCase("AppsUseLightTheme") &&
                    tokens[1].equalsIgnoreCase("REG_DWORD")) {
                try { return Long.decode(tokens[2]) == 0; }
                catch (NumberFormatException ignored) { return null; }
            }
        }
        return null;
    }

    static Boolean parseColorScheme(String output) {
        if (output == null) return null;
        String value = output.trim().replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
        if (value.equals("prefer-dark")) return true;
        if (value.equals("prefer-light")) return false;
        return null;
    }

    /** Commands are fixed argument vectors, never a shell, and bounded to avoid blocking shutdown. */
    private static String query(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) return null;
            // macOS light mode uses a missing preference; its error output deliberately
            // contains no standalone "dark" value and therefore resolves to light.
            if (process.exitValue() != 0 && !command[0].equals("defaults")) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while (bytes.size() < 8192 && (count = input.read(buffer, 0,
                        Math.min(buffer.length, 8192 - bytes.size()))) != -1) bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
