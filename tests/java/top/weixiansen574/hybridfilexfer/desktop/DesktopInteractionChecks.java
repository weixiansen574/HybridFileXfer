package top.weixiansen574.hybridfilexfer.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Hidden-window interaction checks. Never changes OS focus or the system clipboard. */
final class DesktopInteractionChecks {
    private static final String CHINESE_LOG = "连接成功：中文目录 / 测试文件.txt\n校验完成，等待下一次传输。";

    private DesktopInteractionChecks() { }

    static void verifyThemes(DesktopApp app, JFrame frame, Path directory, String phase) throws Exception {
        JComboBox<?> themes = findThemeSelector(frame.getContentPane());
        if (themes == null) throw new AssertionError("The theme selector is unavailable");
        Object original = themes.getSelectedItem();
        JTextArea logs = (JTextArea) field(app, "logs");
        String originalLogs = logs.getText();
        JTextComponent directoryInput = (JTextComponent) field(app, "directory");
        JTextComponent pairingInput = (JTextComponent) field(app, "pairing");
        String originalDirectory = directoryInput.getText(), originalPairing = pairingInput.getText();
        directoryInput.setText("C:\\测试目录\\照片");
        pairingInput.setText("SMOKEPAIRINGCODE400");
        logs.setText(CHINESE_LOG);
        List<Object> before = snapshot(app);
        Object controller = field(app, "controller");
        try {
            for (String theme : new String[]{"LIGHT", "DARK", "LIGHT"}) {
                chooseTheme(themes, theme);
                frame.validate();
                if (controller != field(app, "controller") || !before.equals(snapshot(app)))
                    throw new AssertionError("Theme switch changed connection/input/transfer state: " + theme);
                verifyReadOnly(app, frame);
                String prefix = "ui-" + theme.toLowerCase(java.util.Locale.ROOT) + "-" + phase;
                render(frame, directory.resolve(prefix + ".png"));
                verifyThemeColors(app);
                if ("idle".equals(phase)) {
                    renderFocused(frame, (JComponent) field(app, "connect"), directory.resolve(prefix + "-focus-button.png"));
                    renderFocused(frame, (JComponent) field(app, "reconnect"), directory.resolve(prefix + "-focus-checkbox.png"));
                    renderLogs(frame, logs, directory.resolve(prefix + "-logs.png"));
                } else {
                    assertEnabled(app, "stop");
                    assertEnabled(app, "cancelTransfer");
                }
            }
        } finally {
            themes.setSelectedItem(original);
            logs.setText(originalLogs);
            directoryInput.setText(originalDirectory);
            pairingInput.setText(originalPairing);
        }
        System.out.println("Hidden theme/read-only checks passed: " + phase);
    }

    private static List<Object> snapshot(DesktopApp app) throws Exception {
        List<Object> values = new ArrayList<>();
        for (String name : new String[]{"address", "directory", "pairing", "port", "localPort", "status", "logs"})
            values.add(((JTextComponent) field(app, name)).getText());
        for (String name : new String[]{"connect", "stop", "cancelTransfer", "reconnect", "launchPhone"}) {
            AbstractButton button = (AbstractButton) field(app, name);
            values.add(button.isEnabled()); values.add(button.isSelected());
        }
        JComboBox<?> mode = (JComboBox<?>) field(app, "mode");
        values.add(mode.getSelectedIndex());
        JProgressBar progress = (JProgressBar) field(app, "progress");
        values.add(progress.getValue()); values.add(progress.getString()); values.add(progress.isIndeterminate());
        for (String name : new String[]{"file", "phase", "progressPercent", "connectionBadge", "transferBadge"})
            values.add(((JLabel) field(app, name)).getText());
        for (String name : new String[]{"transferActive", "taskStarted", "lastTransferredBytes", "sessionSecret"})
            values.add(field(app, name));
        javax.swing.table.DefaultTableModel channels =
                (javax.swing.table.DefaultTableModel) field(app, "channels");
        values.add(channels.getRowCount());
        for (int row = 0; row < channels.getRowCount(); row++)
            for (int col = 0; col < channels.getColumnCount(); col++) values.add(channels.getValueAt(row, col));
        return values;
    }

    private static void verifyReadOnly(DesktopApp app, JFrame frame) throws Exception {
        JTextArea status = (JTextArea) field(app, "status");
        if (status.isEditable() || status.isFocusable() || status.getCaret().isVisible())
            throw new AssertionError("Status text allows editing, focus, or an insertion caret");
        JTextArea logs = (JTextArea) field(app, "logs");
        if (logs.isEditable() || !logs.isFocusable())
            throw new AssertionError("Logs must remain selectable but read-only");
        if (logs.getFont().canDisplayUpTo(CHINESE_LOG) != -1)
            throw new AssertionError("Log font lacks Chinese glyphs: " + logs.getFont());
        Clipboard localClipboard = new Clipboard("hfx-visual-test");
        logs.selectAll();
        logs.getTransferHandler().exportToClipboard(logs, localClipboard, TransferHandler.COPY);
        if (!CHINESE_LOG.equals(localClipboard.getData(DataFlavor.stringFlavor)))
            throw new AssertionError("Read-only log selection cannot be copied");
        logs.select(0, 0);
        KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        try {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new SyntheticFocus(frame, logs));
            FocusEvent gained = new FocusEvent(logs, FocusEvent.FOCUS_GAINED);
            for (FocusListener listener : logs.getFocusListeners()) listener.focusGained(gained);
            if (logs.getCaret().isVisible())
                throw new AssertionError("Focusing read-only logs shows an insertion caret");
        } finally {
            FocusEvent lost = new FocusEvent(logs, FocusEvent.FOCUS_LOST);
            for (FocusListener listener : logs.getFocusListeners()) listener.focusLost(lost);
            KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
        }
    }

    private static void verifyThemeColors(DesktopApp app) throws Exception {
        String[][] backgroundKeys = {{"directory", "TextField.background"},
                {"pairing", "PasswordField.background"}, {"browse", "Button.background"},
                {"progress", "ProgressBar.background"}};
        for (String[] pair : backgroundKeys) {
            JComponent component = (JComponent) field(app, pair[0]);
            if (!component.isEnabled() && !(component instanceof JTextComponent)) continue;
            String key = component.isEnabled() ? pair[1] : pair[1].replace(".background", ".disabledBackground");
            Color actual = component.getBackground();
            Color expected = UIManager.getColor(key);
            if (!expected.equals(actual))
                throw new AssertionError("Theme color remains stale on " + pair[0] + ": " + actual + " instead of " + expected);
        }
        assertContrast(UIManager.getColor("Button.default.focusedForeground"),
                UIManager.getColor("Button.default.focusedBackground"), 4.5, "Focused primary button");
        assertContrast(UIManager.getColor("CheckBox.icon.checkmarkColor"),
                UIManager.getColor("CheckBox.icon.focusedSelectedBackground"), 3, "Focused selected checkbox");
    }

    private static void assertContrast(Color foreground, Color background, double minimum, String name) {
        if (foreground == null || background == null) throw new AssertionError(name + " colors are absent");
        double a = luminance(foreground), b = luminance(background);
        double contrast = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
        if (contrast < minimum) throw new AssertionError(name + " contrast is " + contrast);
    }

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }
    private static double linear(int value) {
        double channel = value / 255.0;
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }

    private static void renderFocused(JFrame frame, JComponent target, Path path) throws Exception {
        if (!target.isEnabled()) throw new AssertionError("Focus preview target is disabled");
        KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        try {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(new SyntheticFocus(frame, target));
            if (!com.formdev.flatlaf.ui.FlatUIUtils.isPermanentFocusOwner(target))
                throw new AssertionError("Hidden focus simulation did not reach FlatLaf");
            render(frame, path);
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
        }
    }

    private static void renderLogs(JFrame frame, JTextArea logs, Path path) throws Exception {
        Container parent = logs;
        while (parent != null && !(parent instanceof JTabbedPane)) parent = parent.getParent();
        if (parent == null) throw new AssertionError("Log tab is missing");
        JTabbedPane tabs = (JTabbedPane) parent;
        int selected = tabs.getSelectedIndex();
        Container tab = logs;
        while (tab.getParent() != tabs) tab = tab.getParent();
        try {
            tabs.setSelectedComponent(tab); frame.validate();
            render(frame, path);
        } finally {
            tabs.setSelectedIndex(selected); frame.validate();
        }
    }

    private static JComboBox<?> findThemeSelector(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComboBox) {
                JComboBox<?> combo = (JComboBox<?>) child;
                if (combo.getItemCount() > 0 && combo.getItemAt(0) instanceof Enum
                        && combo.getItemAt(0).getClass().getSimpleName().equals("ThemePreference")) return combo;
            }
            if (child instanceof Container) {
                JComboBox<?> found = findThemeSelector((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void chooseTheme(JComboBox<?> themes, String name) {
        for (int i = 0; i < themes.getItemCount(); i++)
            if (((Enum<?>) themes.getItemAt(i)).name().equals(name)) {
                themes.setSelectedIndex(i);
                return;
            }
        throw new AssertionError("Theme option is missing: " + name);
    }

    private static void assertEnabled(DesktopApp app, String name) throws Exception {
        if (!((JComponent) field(app, name)).isEnabled())
            throw new AssertionError("Theme switch disabled transfer action: " + name);
    }

    private static Object field(DesktopApp app, String name) throws Exception {
        Field field = DesktopApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(app);
    }

    private static void render(JFrame frame, Path path) throws Exception {
        Container content = frame.getContentPane();
        BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            content.printAll(graphics);
        } finally { graphics.dispose(); }
        ImageIO.write(image, "png", path.toFile());
    }

    private static final class SyntheticFocus extends DefaultKeyboardFocusManager {
        private final Window window;
        private final Component owner;
        SyntheticFocus(Window window, Component owner) { this.window = window; this.owner = owner; }
        @Override public Window getActiveWindow() { return window; }
        @Override public Window getFocusedWindow() { return window; }
        @Override public Component getFocusOwner() { return owner; }
        @Override public Component getPermanentFocusOwner() { return owner; }
    }
}
