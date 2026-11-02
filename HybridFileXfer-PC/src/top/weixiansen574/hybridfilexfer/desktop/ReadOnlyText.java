package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import java.awt.Cursor;
import java.awt.Graphics;

/** 只读文本区域设置工具：支持鼠标选中与复制，同时隐藏输入闪烁光标。 */
final class ReadOnlyText {
    private ReadOnlyText() { }

    static void configure(JTextArea area, boolean selectable) {
        area.setEditable(false);
        area.setFocusable(selectable);
        area.setCursor(Cursor.getDefaultCursor());
        DefaultCaret caret = new DefaultCaret() {
            @Override public void paint(Graphics graphics) { /* 只读区域不绘制闪烁光标 */ }
            @Override public void setVisible(boolean visible) { super.setVisible(false); }
        };
        caret.setBlinkRate(0);
        area.setCaret(caret);
    }
}
