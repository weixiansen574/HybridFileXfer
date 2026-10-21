package top.weixiansen574.hybridfilexfer.desktop;

import com.formdev.flatlaf.FlatLaf;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Palette, retained-control refresh, unmodified branding, and live monitor lifecycle. */
public final class DesktopThemeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(ThemePreference.fromString(null) == ThemePreference.SYSTEM, "missing preference follows system");
        check(ThemePreference.fromString("invalid") == ThemePreference.SYSTEM, "unknown preference follows system");
        check(ThemePreference.fromString(" dark ") == ThemePreference.DARK, "saved preference is tolerant");
        check(Boolean.TRUE.equals(SystemThemeMonitor.parseWindows(
                "\n    AppsUseLightTheme    REG_DWORD    0x0\n")), "Windows app theme dark");
        check(Boolean.FALSE.equals(SystemThemeMonitor.parseWindows(
                "\n    AppsUseLightTheme    REG_DWORD    0x1\n")), "Windows app theme light");
        check(SystemThemeMonitor.parseWindows("unrelated REG_DWORD 0x0") == null, "unrelated registry value ignored");
        check(SystemThemeMonitor.parseWindows("AppsUseLightTheme REG_DWORD garbage") == null, "malformed value ignored");
        check(Boolean.TRUE.equals(SystemThemeMonitor.parseColorScheme("'prefer-dark'")), "GNOME dark");
        check(Boolean.FALSE.equals(SystemThemeMonitor.parseColorScheme("'prefer-light'")), "GNOME light");
        check(SystemThemeMonitor.parseColorScheme("'default'") == null, "GNOME default falls back to GTK theme");
        SwingUtilities.invokeAndWait(DesktopThemeTest::verifyThemeAndRetainedControls);
        verifyOriginalLogo();
        verifyMonitor();
        System.out.println("DesktopThemeTest: " + checks + " checks passed");
    }

    private static void verifyThemeAndRetainedControls() {
        DesktopTheme.configure(ThemePreference.LIGHT);
        JPanel panel = new JPanel();
        JLabel label = DesktopTheme.label("Transfer", 13, false, DesktopTheme.TEXT);
        JTextField input = new JTextField("preserved endpoint");
        JTextField defaultInput = new JTextField("unbound input");
        JButton defaultButton = new JButton("Default button");
        JPanel defaultPanel = new JPanel();
        DesktopTheme.Pill pill = new DesktopTheme.Pill();
        pill.state("Connected", () -> DesktopTheme.GREEN, () -> DesktopTheme.GREEN_SOFT);
        DesktopTheme.bindBackground(panel, () -> DesktopTheme.BACKGROUND);
        DesktopTheme.bindForeground(input, () -> DesktopTheme.TEXT);
        panel.add(label); panel.add(input); panel.add(pill);
        panel.add(defaultInput); panel.add(defaultButton); panel.add(defaultPanel);
        Color lightText = label.getForeground();
        for (ThemePreference mode : new ThemePreference[]{ThemePreference.DARK, ThemePreference.LIGHT}) {
            DesktopTheme.configure(mode);
            SwingUtilities.updateComponentTreeUI(panel);
            DesktopTheme.refreshColors(panel);
            check(FlatLaf.isLafDark() == (mode == ThemePreference.DARK), "FlatLaf matches requested appearance");
            check(label.getForeground().equals(DesktopTheme.TEXT), "label semantic role refreshed");
            check(panel.getBackground().equals(DesktopTheme.BACKGROUND), "background binding refreshed");
            check(input.getForeground().equals(DesktopTheme.TEXT), "input binding refreshed");
            check(defaultInput.getBackground().equals(DesktopTheme.SURFACE), "unbound input background refreshed");
            check(defaultInput.getForeground().equals(DesktopTheme.TEXT), "unbound input foreground refreshed");
            check(defaultButton.getForeground().equals(DesktopTheme.TEXT), "unbound button foreground refreshed");
            check(defaultPanel.getBackground().equals(DesktopTheme.SURFACE), "unbound panel background refreshed");
            check(defaultInput.getBackground() instanceof javax.swing.plaf.UIResource,
                    "look and feel colors remain replaceable resources");
            check(pill.getForeground().equals(DesktopTheme.GREEN), "pill foreground refreshed");
            check(input.getText().equals("preserved endpoint"), "switch preserves user input");
            check(contrast(DesktopTheme.TEXT, DesktopTheme.SURFACE) >= 7, "body text contrast");
            check(contrast(DesktopTheme.MUTED, DesktopTheme.SURFACE) >= 4.4, "secondary text contrast");
            check(UIManager.getInt("Component.focusWidth") >= 3, "visible keyboard focus ring");
            check(contrast(UIManager.getColor("Component.focusColor"), DesktopTheme.SURFACE) >= 4.5,
                    "focus ring contrast");
            check(UIManager.getInt("CheckBox.icon.focusWidth") >= 3, "checkbox focus ring width");
            check(contrast(UIManager.getColor("Button.default.focusedForeground"),
                    UIManager.getColor("Button.default.focusedBackground")) >= 4.5, "focused primary button readable");
            check(UIManager.getColor("Button.default.focusedForeground").equals(Color.WHITE),
                    "focused primary button preserves explicit foreground");
            check(UIManager.getColor("CheckBox.icon.focusColor").equals(UIManager.getColor("Component.focusColor")),
                    "checkbox uses high contrast focus color");
            pill.setSize(150, 34);
            BufferedImage rendered = new BufferedImage(150, 34, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = rendered.createGraphics();
            pill.paint(g); g.dispose();
            check(rendered.getRGB(75, 3) == DesktopTheme.GREEN_SOFT.getRGB(), "pill fill follows current palette");
        }
        check(label.getForeground().equals(lightText), "round trip restores light semantic colors");
    }

    private static void verifyOriginalLogo() throws Exception {
        BufferedImage source = ImageIO.read(DesktopTheme.class.getResourceAsStream("logo.png"));
        check(source.getWidth() == 512 && source.getHeight() == 512, "original logo dimensions preserved");
        BufferedImage actual = DesktopTheme.applicationIcon(512);
        boolean identical = true;
        for (int y = 0; y < 512 && identical; y++)
            for (int x = 0; x < 512; x++)
                if (source.getRGB(x, y) != actual.getRGB(x, y)) { identical = false; break; }
        check(identical, "native-size icon preserves every original pixel");
        SwingUtilities.invokeAndWait(() -> DesktopTheme.configure(ThemePreference.DARK));
        BufferedImage dark = DesktopTheme.applicationIcon(512);
        check(dark.getRGB(256, 256) == source.getRGB(256, 256), "dark theme never recolors original artwork");
    }

    private static void verifyMonitor() throws Exception {
        SwingUtilities.invokeAndWait(() -> DesktopTheme.configure(ThemePreference.SYSTEM));
        final boolean desired = !DesktopTheme.isDark();
        CountDownLatch changed = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger();
        SystemThemeMonitor monitor = new SystemThemeMonitor(() -> {
            check(SwingUtilities.isEventDispatchThread(), "monitor refresh callback runs on EDT");
            changes.incrementAndGet(); changed.countDown();
        }, () -> desired);
        monitor.start();
        check(changed.await(4, TimeUnit.SECONDS), "system preference live change applied");
        check(DesktopTheme.isDark() == desired, "monitor installs detected appearance");
        monitor.close();
        int before = changes.get();
        SwingUtilities.invokeAndWait(() -> { });
        check(changes.get() == before, "closed monitor cannot refresh afterward");

        CountDownLatch detecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SystemThemeMonitor racing = new SystemThemeMonitor(() -> {
            throw new AssertionError("explicit choice was overridden");
        }, () -> {
            detecting.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Boolean.TRUE;
        });
        racing.start();
        check(detecting.await(3, TimeUnit.SECONDS), "detector started while following system");
        SwingUtilities.invokeAndWait(() -> DesktopTheme.configure(ThemePreference.LIGHT));
        release.countDown();
        racing.close();
        SwingUtilities.invokeAndWait(() -> { });
        check(!DesktopTheme.isDark() && DesktopTheme.getPreference() == ThemePreference.LIGHT,
                "explicit choice wins over in-flight OS detection");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (themeThreadAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        check(!themeThreadAlive(), "all closed theme monitor threads terminate");
    }

    private static boolean themeThreadAlive() {
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().equals("hfx-system-theme")) return true;
        return false;
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
