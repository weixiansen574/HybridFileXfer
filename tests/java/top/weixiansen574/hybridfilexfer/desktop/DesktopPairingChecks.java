package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises real Swing fields and request guards without starting ADB or a connection worker. */
final class DesktopPairingChecks {
    private DesktopPairingChecks() { }
    private static int checks;

    @SuppressWarnings("unchecked")
    static void verify(DesktopApp app) throws Exception {
        check(SwingUtilities.isEventDispatchThread(), "Pairing checks must run on the Swing thread");
        JComboBox<Object> mode = (JComboBox<Object>) get(app, "mode");
        JComboBox<Object> devices = (JComboBox<Object>) get(app, "devices");
        JPasswordField pairing = (JPasswordField) get(app, "pairing");
        PortField port = (PortField) get(app, "port");
        JButton connect = (JButton) get(app, "connect");
        JButton openPhone = (JButton) get(app, "openPhone");
        ConnectionController controller = (ConnectionController) get(app, "controller");
        check(!controller.isRunning(), "Smoke test must not own a real connection");
        ComboBoxModel<Object> originalDevices = devices.getModel();
        int originalMode = mode.getSelectedIndex();
        String originalCode = new String(pairing.getPassword()), originalPortText = port.getText();
        Object originalPort = port.getValue(), originalAttempt = get(controller, "current");
        boolean originalConnectEnabled = connect.isEnabled(), originalOpenEnabled = openPhone.isEnabled();
        Map<String, Object> saved = new LinkedHashMap<>();
        for (String name : new String[]{"automaticSecret", "sessionSecret", "phoneRequestGeneration",
                "phoneRequestInFlight", "pendingPhonePairing", "pendingPhoneDevice", "closing"})
            saved.put(name, get(app, name));
        try {
            mode.setSelectedIndex(0);
            set(app, "closing", false);
            set(app, "phoneRequestInFlight", false);
            AdbBridge.Device first = new AdbBridge.Device("synthetic-phone-a", "device", "Synthetic A");
            AdbBridge.Device second = new AdbBridge.Device("synthetic-phone-b", "device", "Synthetic B");
            devices.setModel(new DefaultComboBoxModel<>(new Object[]{first, second}));
            devices.setSelectedItem(first);
            pairing.setText("");
            port.setValue(5740);
            int generation = (Integer) get(app, "phoneRequestGeneration");
            String enteredPort = port.getText();
            check(current(app, generation, first.serial, "", enteredPort), "Unchanged request must be accepted");

            pairing.setText("SYNTHETIC_MANUAL_CODE");
            check(!current(app, generation, first.serial, "", enteredPort), "A later manual code rejects an old result");
            pairing.setText("");
            port.setText("5741");
            check(!current(app, generation, first.serial, "", enteredPort), "A changed port rejects an old result");
            port.setText(enteredPort);
            devices.setSelectedItem(second);
            check(!current(app, generation, first.serial, "", enteredPort), "A changed device rejects an old result");
            devices.setSelectedItem(first);
            set(app, "phoneRequestGeneration", generation + 1);
            check(!current(app, generation, first.serial, "", enteredPort), "An older generation is rejected");
            set(app, "phoneRequestGeneration", generation);
            set(app, "closing", true);
            check(!current(app, generation, first.serial, "", enteredPort), "Closing rejects queued results");
            set(app, "closing", false);
            mode.setSelectedIndex(1);
            check(!current(app, generation, first.serial, "", enteredPort), "Network mode rejects ADB results");
            mode.setSelectedIndex(0);
            generation = (Integer) get(app, "phoneRequestGeneration");
            check(current(app, generation, first.serial, "", enteredPort), "A fresh request works after a mode change");

            // Only populate the controller's state object: never call start() or create a worker.
            Class<?> attemptClass = Class.forName(ConnectionController.class.getName() + "$Attempt");
            Constructor<?> constructor = attemptClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            set(controller, "current", constructor.newInstance());
            check(!current(app, generation, first.serial, "", enteredPort), "An active connection rejects auxiliary results");
            set(controller, "current", originalAttempt);

            set(app, "automaticSecret", "");
            invoke(app, "displayPairing", new Class<?>[]{String.class, int.class}, "SYNTHETIC_AUTO_CODE_ONE", 5742);
            check("".equals(invoke(app, "obtainSecret")), "Displayed automatic code must remain automatic");
            check(new String(pairing.getPassword()).equals("SYNTHETIC_AUTO_CODE_ONE"), "Automatic code is visible in its field");
            check(((Integer) port.getValue()) == 5742, "Phone service port is applied");
            invoke(app, "displayPairing", new Class<?>[]{String.class, int.class}, "SYNTHETIC_AUTO_CODE_TWO", 5743);
            check("".equals(invoke(app, "obtainSecret")), "A refreshed automatic code must remain automatic");
            check("SYNTHETIC_AUTO_CODE_TWO".equals(get(app, "sessionSecret")), "Log redaction follows the current code");
            pairing.setText("SYNTHETIC_MANUAL_CODE");
            check("SYNTHETIC_MANUAL_CODE".equals(invoke(app, "obtainSecret")), "Manual override must be retained");
            invoke(app, "displayPairing", new Class<?>[]{String.class, int.class}, "SYNTHETIC_MANUAL_CODE", 5743);
            check("SYNTHETIC_MANUAL_CODE".equals(invoke(app, "obtainSecret")), "Confirming an explicit code must not mark it automatic");
            pairing.setText("SYNTHETIC_AUTO_CODE_TWO");
            mode.setSelectedIndex(1);
            check("SYNTHETIC_AUTO_CODE_TWO".equals(invoke(app, "obtainSecret")), "Network mode needs the actual code");
            mode.setSelectedIndex(0);

            AdbPairingSession pending = new AdbPairingSession("", 5743);
            set(app, "pendingPhonePairing", pending);
            set(app, "pendingPhoneDevice", first.serial);
            set(app, "phoneRequestInFlight", true);
            generation = (Integer) get(app, "phoneRequestGeneration");
            invoke(app, "begin");
            invoke(app, "requestPhone");
            check(!controller.isRunning() && get(controller, "current") == originalAttempt,
                    "In-flight guards must not start a connection worker");
            check((Integer) get(app, "phoneRequestGeneration") == generation,
                    "In-flight guards must not submit another phone request");
            check(get(app, "pendingPhonePairing") == pending, "In-flight guards preserve the pending confirmation");
            set(app, "phoneRequestInFlight", false);
            mode.setSelectedIndex(1);
            check(get(app, "pendingPhonePairing") == null, "Changing mode revokes the pending ADB preparation");
        } finally {
            set(controller, "current", originalAttempt);
            mode.setSelectedIndex(originalMode);
            devices.setModel(originalDevices);
            pairing.setText(originalCode);
            port.setValue(originalPort);
            port.setText(originalPortText);
            for (Map.Entry<String, Object> entry : saved.entrySet()) set(app, entry.getKey(), entry.getValue());
            connect.setEnabled(originalConnectEnabled);
            openPhone.setEnabled(originalOpenEnabled);
        }
        System.out.println("Hidden Swing pairing checks passed: " + checks + " (no ADB or device access)");
    }

    private static boolean current(DesktopApp app, int generation, String device, String code, String port)
            throws Exception {
        return (Boolean) invoke(app, "phoneRequestCurrent",
                new Class<?>[]{int.class, String.class, String.class, String.class}, generation, device, code, port);
    }

    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(DesktopApp app, String name) throws Exception {
        return invoke(app, name, new Class<?>[0]);
    }

    private static Object invoke(DesktopApp app, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = DesktopApp.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(app, args);
    }
}
