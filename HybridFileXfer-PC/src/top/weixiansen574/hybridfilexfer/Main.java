package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.desktop.ConnectionController;
import top.weixiansen574.hybridfilexfer.desktop.DesktopApp;
import top.weixiansen574.hybridfilexfer.desktop.LaunchOptions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop by default; existing -c/-s/-d commands continue to select CLI mode. */
public final class Main {
    public static void main(String[] args) {
        try {
            LaunchOptions options = LaunchOptions.parse(args);
            if (options.help) { System.out.println(help()); return; }
            if (options.version) {
                String version = Main.class.getPackage().getImplementationVersion();
                System.out.println("HybridFileXfer " + (version == null ? "development build" : version));
                return;
            }
            if (options.gui) { DesktopApp.launch(options); return; }
            int result = runCli(options);
            if (result != 0) System.exit(result);
        } catch (Exception e) {
            System.err.println(ConnectionController.friendlyError(e));
            System.err.println("Use --help for options.");
            System.exit(2);
        }
    }
    private static int runCli(LaunchOptions options) throws Exception {
        if (options.secret.isEmpty()) {
            String environmentCode = System.getenv("HFX_PAIRING_CODE");
            if (environmentCode != null) options.secret = environmentCode.trim();
        }
        if (options.secret.isEmpty() && !"adb".equals(options.connect) && System.console() != null) {
            char[] code = System.console().readPassword("Phone pairing code: ");
            if (code != null) { options.secret = new String(code).trim(); java.util.Arrays.fill(code, '\0'); }
        }
        ConnectionController.Settings settings = new ConnectionController.Settings(options.connect, options.device,
                options.directory, options.secret, options.port, options.localPort, options.wait,
                options.launchPhone, options.burst, new TransferOptions());
        AtomicBoolean connected = new AtomicBoolean(), failed = new AtomicBoolean();
        ConnectionController controller = new ConnectionController(new ConnectionController.Listener() {
            @Override public void onState(ConnectionController.State state, String message) {
                System.out.println(message);
                if (state == ConnectionController.State.CONNECTED) connected.set(true);
                if (state == ConnectionController.State.ERROR) failed.set(true);
            }
            @Override public void onLog(String message) { System.out.println(message); }
            @Override public void onComplete(boolean upload, long bytes, long millis) {
                System.out.printf("%s: %d bytes, %d ms%n", upload ? "Sent" : "Received", bytes, millis);
            }
            @Override public void onTransferFailed(String message) { failed.set(true); System.err.println(message); }
        });
        Thread shutdown = new Thread(controller::close, "hfx-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            controller.start(settings);
            while (!controller.await(1, TimeUnit.SECONDS)) { /* Ctrl+C invokes the shutdown hook. */ }
            return connected.get() && !failed.get() ? 0 : 1;
        } finally {
            controller.close();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }
    public static String help() {
        return "HybridFileXfer — desktop connection and transfer console\n\n"
                + "  java -jar HybridFileXfer.jar                         Open desktop GUI\n"
                + "  java -jar HybridFileXfer.jar --gui                   Open desktop GUI\n"
                + "  java -jar HybridFileXfer.jar -c adb --launch-phone --wait\n"
                + "  java -jar HybridFileXfer.jar -c 192.168.1.2 --pairing-code-file code.txt\n\n"
                + "Options:\n"
                + "  -c, --connect ADDRESS      adb or phone IP address (selects CLI)\n"
                + "  -s, --device ID            ADB device serial (--switch-device also accepted)\n"
                + "  -d, --dir DIRECTORY        Computer folder; CLI default / (GUI uses Downloads)\n"
                + "  -p, --port PORT            Phone server port (default 5740)\n"
                + "      --local-port PORT      ADB local port; 0 automatically allocates a free port\n"
                + "      --pairing-code CODE    Phone code (also HFX_PAIRING_CODE environment variable)\n"
                + "      --pairing-code-file F  Read a UTF-8 file, keeping the code out of process arguments\n"
                + "                             ADB reads the current phone code automatically when omitted\n"
                + "      --launch-phone         Ask the phone to open and confirm a connection via ADB\n"
                + "      --wait                 Wait/reconnect with bounded backoff; Ctrl+C stops\n"
                + "      --adb-burst            Experimental ADB_BURST_MODE=1 for our ADB subprocesses\n"
                + "      --cli                  Explicit CLI mode\n"
                + "  -h, --help                 Show help\n"
                + "  -v, --version              Show build version\n\n"
                + "Reconnect does not replay transfers. Select the task again on the phone.\n"
                + "The Android and desktop apps must use the same protocol version.\n"
                + "ADB burst never kills or restarts the shared ADB server.";
    }
}
