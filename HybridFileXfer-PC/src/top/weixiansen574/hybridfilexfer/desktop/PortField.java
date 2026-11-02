package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.JFormattedTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatterFactory;
import java.text.ParseException;
import java.util.Objects;

/** 端口号输入框，支持多视图共享同一个端口数值模型。 */
final class PortField extends JFormattedTextField {
    private final SpinnerNumberModel model;

    PortField(int value, int minimum, int maximum) {
        this(new SpinnerNumberModel(value, minimum, maximum, 1));
    }

    PortField(SpinnerNumberModel model) {
        this.model = model;
        setFormatterFactory(new DefaultFormatterFactory(new AbstractFormatter() {
            @Override public Object stringToValue(String text) throws ParseException {
                String value = text == null ? "" : text.trim();
                int minimum = ((Number) model.getMinimum()).intValue();
                int maximum = ((Number) model.getMaximum()).intValue();
                if (!value.matches("[0-9]{1,5}")) throw invalid(minimum, maximum);
                int port = Integer.parseInt(value);
                if (port < minimum || port > maximum) throw invalid(minimum, maximum);
                return port;
            }
            @Override public String valueToString(Object value) {
                return value == null ? "" : value.toString();
            }
        }));
        // 失去焦点时保留用户当前输入的文本，由连接操作触发显式校验，避免静默回退到旧端口。
        setFocusLostBehavior(PERSIST);
        setColumns(6);
        setHorizontalAlignment(LEFT);
        setValue(model.getValue());
        addPropertyChangeListener("value", event -> {
            if (!Objects.equals(model.getValue(), getValue())) model.setValue(getValue());
        });
        model.addChangeListener(event -> {
            if (!Objects.equals(getValue(), model.getValue())) setValue(model.getValue());
        });
    }

    SpinnerNumberModel getModel() { return model; }

    private static ParseException invalid(int minimum, int maximum) {
        return new ParseException(DesktopText.text("端口必须是 ", "Port must be an integer from ")
                + minimum + DesktopText.text(" 到 ", " to ") + maximum
                + DesktopText.text(" 之间的整数。", "."), 0);
    }
}
