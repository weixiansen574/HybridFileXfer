package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.JFormattedTextField;
import javax.swing.SwingUtilities;
import java.text.ParseException;

/** Direct entry must validate current text, not silently reuse a previously valid port. */
public final class PortFieldTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void enter(PortField field, String text, int expected) throws ParseException {
        field.setText(text);
        field.commitEdit();
        check(field.getValue().equals(expected), "Accepted port " + text);
    }
    private static void reject(PortField field, String text) {
        Object previous = field.getModel().getValue();
        field.setText(text);
        try {
            field.commitEdit();
            throw new AssertionError("Invalid port accepted: " + text);
        } catch (ParseException expected) {
            check(field.getModel().getValue().equals(previous), "Invalid text cannot change the committed port");
            check(field.getText().equals(text), "Invalid text remains visible for correction");
        }
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                PortField main = new PortField(5740, 1, 65535);
                PortField advanced = new PortField(main.getModel());
                PortField local = new PortField(0, 0, 65535);
                check(main.getFocusLostBehavior() == JFormattedTextField.PERSIST
                                && advanced.getFocusLostBehavior() == JFormattedTextField.PERSIST,
                        "Focus loss preserves uncommitted input until explicit validation");
                enter(main, "65535", 65535);
                check(advanced.getValue().equals(65535) && advanced.getText().equals("65535"),
                        "Main port commits update the advanced editor");
                enter(advanced, "1", 1);
                check(main.getValue().equals(1) && main.getText().equals("1"),
                        "Advanced port commits update the main editor");
                for (String invalid : new String[]{"0", "65536", "-1", "abc", "", "5740.5", "5,740"})
                    reject(main, invalid);
                reject(advanced, "65536");
                enter(main, "5740", 5740);
                check(advanced.getValue().equals(5740), "Both editors recover after correcting input");
                enter(local, "0", 0);
                enter(local, "65535", 65535);
                reject(local, "-1");
                reject(local, "65536");
                reject(local, "automatic");
            } catch (ParseException failure) { throw new AssertionError(failure); }
        });
        System.out.println("PortFieldTest: " + checks + " checks passed");
    }
}
