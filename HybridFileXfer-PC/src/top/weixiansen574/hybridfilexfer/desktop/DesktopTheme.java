package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/** Small drawing primitives shared by the desktop's card layout. */
public final class DesktopTheme {
    static Color BACKGROUND, SURFACE, TEXT, MUTED, BLUE, BLUE_SOFT, BORDER;
    static Color GREEN, GREEN_SOFT, RED, RED_SOFT, NEUTRAL_SOFT, LOG_BACKGROUND;
    private static final String FOREGROUND_BINDING = "hybridfilexfer.theme.foreground";
    private static final String BACKGROUND_BINDING = "hybridfilexfer.theme.background";
    private static volatile ThemePreference preference = ThemePreference.SYSTEM;
    private static volatile boolean dark;
    static { palette(false); }

    private DesktopTheme() { }

    public static ThemePreference getPreference() { return preference; }
    public static boolean isDark() { return dark; }

    /** Call on the EDT before creating controls, or before refreshing existing component trees. */
    public static void configure(ThemePreference mode) {
        if (mode == null) mode = ThemePreference.SYSTEM;
        boolean requestedDark = mode == ThemePreference.DARK ||
                (mode == ThemePreference.SYSTEM && SystemThemeMonitor.currentSystemDark());
        preference = mode;
        palette(requestedDark);
        if (!(requestedDark ? FlatDarkLaf.setup() : FlatLightLaf.setup()))
            throw new IllegalStateException("The desktop theme could not be initialized.");
        String family = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "Microsoft YaHei UI" : Font.DIALOG;
        putDefault("defaultFont", new FontUIResource(family, Font.PLAIN, 13));
        putDefault("Component.arc", 10);
        putDefault("Button.arc", 10);
        putDefault("TextComponent.arc", 10);
        putDefault("Component.focusWidth", 3);
        putDefault("Component.innerFocusWidth", 0);
        Color focus = new Color(dark ? 0xACCCFF : 0x1648B8);
        Color primary = new Color(dark ? 0x3576E6 : 0x2764E7);
        Color primaryHover = new Color(dark ? 0x427FEA : 0x1D54CA);
        Color primaryFocused = new Color(dark ? 0x2456AA : 0x1749B6);
        putDefault("Component.focusColor", focus);
        putDefault("Component.focusedBorderColor", focus);
        putDefault("Component.borderColor", BORDER);
        putDefault("Component.disabledBorderColor", BORDER);
        putDefault("Component.disabledForeground", MUTED);
        putDefault("Panel.background", SURFACE);
        putDefault("Label.foreground", TEXT);
        putDefault("TextField.foreground", TEXT);
        putDefault("TextField.background", SURFACE);
        putDefault("FormattedTextField.background", SURFACE);
        putDefault("PasswordField.background", SURFACE);
        putDefault("TextField.inactiveBackground", NEUTRAL_SOFT);
        putDefault("PasswordField.inactiveBackground", NEUTRAL_SOFT);
        putDefault("Button.background", SURFACE);
        putDefault("Button.foreground", TEXT);
        putDefault("Button.disabledBackground", NEUTRAL_SOFT);
        putDefault("Button.focusedBackground", BLUE_SOFT);
        putDefault("Button.focusedBorderColor", focus);
        putDefault("Button.default.background", primary);
        putDefault("Button.default.foreground", Color.WHITE);
        putDefault("Button.default.hoverBackground", primaryHover);
        putDefault("Button.default.pressedBackground", primaryFocused);
        putDefault("Button.default.focusedBackground", primaryFocused);
        putDefault("Button.default.focusedForeground", Color.WHITE);
        putDefault("Button.default.focusColor", focus);
        putDefault("Button.default.focusedBorderColor", focus);
        putDefault("Button.default.boldText", true);
        putDefault("Button.margin", new Insets(8, 16, 8, 16));
        putDefault("TextComponent.margin", new Insets(8, 12, 8, 12));
        putDefault("ComboBox.padding", new Insets(8, 12, 8, 12));
        putDefault("ComboBox.background", SURFACE);
        putDefault("ComboBox.buttonBackground", SURFACE);
        putDefault("ComboBox.disabledBackground", NEUTRAL_SOFT);
        putDefault("CheckBox.icon.focusWidth", 3);
        putDefault("CheckBox.icon.focusColor", focus);
        putDefault("CheckBox.icon.focusedBorderColor", focus);
        putDefault("CheckBox.icon.focusedSelectedBorderColor", focus);
        putDefault("CheckBox.icon.selectedBackground", primary);
        putDefault("CheckBox.icon.selectedBorderColor", primary);
        putDefault("CheckBox.icon.focusedSelectedBackground", primaryFocused);
        putDefault("CheckBox.icon.checkmarkColor", Color.WHITE);
        putDefault("ProgressBar.arc", 12);
        putDefault("ProgressBar.foreground", BLUE);
        putDefault("ProgressBar.background", BLUE_SOFT);
        putDefault("ProgressBar.horizontalSize", new Dimension(160, 10));
        putDefault("TabbedPane.background", SURFACE);
        putDefault("TabbedPane.selectedBackground", SURFACE);
        putDefault("TabbedPane.focusColor", BLUE_SOFT);
        putDefault("TabbedPane.underlineColor", BLUE);
        putDefault("TabbedPane.tabHeight", 36);
        putDefault("TabbedPane.contentSeparatorHeight", 1);
        putDefault("TabbedPane.contentAreaColor", BORDER);
        putDefault("Table.showHorizontalLines", false);
        putDefault("Table.showVerticalLines", false);
        putDefault("Table.background", SURFACE);
        putDefault("Table.selectionBackground", BLUE_SOFT);
        putDefault("Table.selectionForeground", TEXT);
        putDefault("TableHeader.background", NEUTRAL_SOFT);
        putDefault("TableHeader.foreground", MUTED);
        putDefault("TableHeader.separatorColor", NEUTRAL_SOFT);
        putDefault("ScrollBar.width", 10);
        putDefault("ScrollPane.border", new EmptyBorder(0, 0, 0, 0));
        putDefault("OptionPane.background", SURFACE);
    }

    private static void palette(boolean useDark) {
        dark = useDark;
        BACKGROUND = new Color(dark ? 0x111722 : 0xF3F6FB);
        SURFACE = new Color(dark ? 0x1C2635 : 0xFFFFFF);
        TEXT = new Color(dark ? 0xE8EFFA : 0x172B4D);
        MUTED = new Color(dark ? 0xA4B3C9 : 0x69788F);
        BLUE = new Color(dark ? 0x8BB7FF : 0x2764E7);
        BLUE_SOFT = new Color(dark ? 0x243D61 : 0xEAF1FF);
        BORDER = new Color(dark ? 0x3A4B63 : 0xDCE3EF);
        GREEN = new Color(dark ? 0x78D7B1 : 0x15825D);
        GREEN_SOFT = new Color(dark ? 0x183F36 : 0xE8F6EF);
        RED = new Color(dark ? 0xFF9AA4 : 0xC2414B);
        RED_SOFT = new Color(dark ? 0x492A36 : 0xFFF0F1);
        NEUTRAL_SOFT = new Color(dark ? 0x232F40 : 0xF7F9FC);
        LOG_BACKGROUND = new Color(dark ? 0x151D29 : 0xF7F9FC);
    }

    /** Swing only replaces defaults marked UIResource when the look and feel changes. */
    private static void putDefault(String key, Object value) {
        if (value instanceof Color) value = new javax.swing.plaf.ColorUIResource((Color) value);
        else if (value instanceof Insets) {
            Insets insets = (Insets) value;
            value = new javax.swing.plaf.InsetsUIResource(insets.top, insets.left, insets.bottom, insets.right);
        } else if (value instanceof Dimension) {
            Dimension dimension = (Dimension) value;
            value = new javax.swing.plaf.DimensionUIResource(dimension.width, dimension.height);
        } else if (value instanceof javax.swing.border.Border)
            value = new javax.swing.plaf.BorderUIResource((javax.swing.border.Border) value);
        UIManager.put(key, value);
    }

    public static void bindForeground(JComponent component, Supplier<Color> color) {
        component.putClientProperty(FOREGROUND_BINDING, color);
        component.setForeground(color.get());
    }

    public static void bindBackground(JComponent component, Supplier<Color> color) {
        component.putClientProperty(BACKGROUND_BINDING, color);
        component.setBackground(color.get());
    }

    @SuppressWarnings("unchecked")
    public static void refreshColors(Container root) {
        if (root instanceof JComponent) {
            JComponent component = (JComponent) root;
            Object foreground = component.getClientProperty(FOREGROUND_BINDING);
            Object background = component.getClientProperty(BACKGROUND_BINDING);
            if (foreground instanceof Supplier) component.setForeground(((Supplier<Color>) foreground).get());
            if (background instanceof Supplier) component.setBackground(((Supplier<Color>) background).get());
        }
        for (Component child : root.getComponents())
            if (child instanceof Container) refreshColors((Container) child);
        root.repaint();
    }

    /** Preserve semantic roles for labels constructed with a current palette color. */
    private static Supplier<Color> role(Color value) {
        if (value == TEXT) return () -> TEXT;
        if (value == MUTED) return () -> MUTED;
        if (value == BLUE) return () -> BLUE;
        if (value == BLUE_SOFT) return () -> BLUE_SOFT;
        if (value == GREEN) return () -> GREEN;
        if (value == GREEN_SOFT) return () -> GREEN_SOFT;
        if (value == RED) return () -> RED;
        if (value == RED_SOFT) return () -> RED_SOFT;
        if (value == BACKGROUND) return () -> BACKGROUND;
        if (value == SURFACE) return () -> SURFACE;
        if (value == BORDER) return () -> BORDER;
        if (value == NEUTRAL_SOFT) return () -> NEUTRAL_SOFT;
        if (value == LOG_BACKGROUND) return () -> LOG_BACKGROUND;
        return () -> value;
    }

    private static final class OriginalLogo {
        static final BufferedImage IMAGE = read();
        private static BufferedImage read() {
            try (InputStream input = DesktopTheme.class.getResourceAsStream("logo.png")) {
                if (input == null) throw new IOException("Missing original application logo");
                BufferedImage image = ImageIO.read(input);
                if (image == null) throw new IOException("Invalid original application logo");
                return image;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load the application logo.", e);
            }
        }
    }

    /** Resize the author's unchanged Android artwork for windows and native packages. */
    public static BufferedImage applicationIcon(int size) {
        if (size < 1 || size > 2048) throw new IllegalArgumentException("Icon size must be 1..2048");
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        BufferedImage source = OriginalLogo.IMAGE;
        double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        graphics.drawImage(source, (size - width) / 2, (size - height) / 2, width, height, null);
        graphics.dispose();
        return image;
    }

    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static JLabel label(String value, float size, boolean bold, Color color) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        bindForeground(label, role(color));
        return label;
    }

    static final class Card extends JPanel {
        Card(LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setBorder(new EmptyBorder(16, 20, 16, 20));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SURFACE);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Keeps the form width responsive and scrolls secondary content on short displays. */
    static final class CardColumn extends JPanel implements Scrollable {
        CardColumn() { super(new GridBagLayout()); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    static final class Pill extends JLabel {
        private Supplier<Color> fill = () -> BLUE_SOFT;
        Pill() {
            setBorder(new EmptyBorder(7, 13, 7, 13));
            setFont(getFont().deriveFont(Font.BOLD, 12f));
            bindForeground(this, () -> BLUE);
        }

        void state(String text, Color foreground, Color background) {
            state(text, role(foreground), role(background));
        }

        void state(String text, Supplier<Color> foreground, Supplier<Color> background) {
            setText(text);
            bindForeground(this, foreground);
            fill = background;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill.get());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class BrandIcon implements Icon {
        private final BufferedImage image = applicationIcon(48);
        @Override public int getIconWidth() { return 48; }
        @Override public int getIconHeight() { return 48; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.drawImage(image, x, y, null);
        }
    }
}
