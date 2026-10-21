package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import java.awt.Cursor;
import java.awt.Graphics;

/** Readable/selectable output without the blinking caret used by editable fields. */
final class ReadOnlyText {
    private ReadOnlyText() { }

    static void configure(JTextArea area, boolean selectable) {
        area.setEditable(false);
        area.setFocusable(selectable);
        area.setCursor(Cursor.getDefaultCursor());
        DefaultCaret caret = new DefaultCaret() {
            @Override public void paint(Graphics graphics) { /* Output has no insertion caret. */ }
            @Override public void setVisible(boolean visible) { super.setVisible(false); }
        };
        caret.setBlinkRate(0);
        area.setCaret(caret);
    }
}
