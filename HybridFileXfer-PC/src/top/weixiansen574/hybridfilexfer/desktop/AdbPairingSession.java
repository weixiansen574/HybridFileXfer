package top.weixiansen574.hybridfilexfer.desktop;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.weixiansen574.hybridfilexfer.security.Pairing;
import static top.weixiansen574.hybridfilexfer.desktop.DesktopText.text;

/** Per-attempt pairing state. Automatic discovery travels only over authorized ADB. */
final class AdbPairingSession {
    interface Phone {
        Snapshot readPairing(String device) throws IOException, InterruptedException;
        void launchPhone(String device, String secret, int port) throws IOException, InterruptedException;
    }

    static final class Snapshot {
        final String state, secret;
        final int port;
        private Snapshot(String state, String secret, int port) {
            this.state = state; this.secret = secret; this.port = port;
        }
    }

    private static final Pattern RESPONSE = Pattern.compile(
            "Result: Bundle\\[\\{hfx_pairing=(HFXPAIR1:[A-Za-z0-9_:\\-]+)\\}\\]");

    /** Never include raw provider output in errors: it may contain a pairing secret. */
    static Snapshot parse(String output) throws IOException {
        Matcher match = RESPONSE.matcher(output.trim());
        if (!match.matches()) throw unavailable();
        String[] fields = match.group(1).split(":", -1);
        if (fields.length == 2 && ("idle".equals(fields[1]) || "busy".equals(fields[1])))
            return new Snapshot(fields[1], "", 0);
        if (fields.length == 4 && "ready".equals(fields[1])) {
            try {
                int port = Integer.parseInt(fields[2]);
                if (port < 1024 || port > 65535) throw new IllegalArgumentException();
                return new Snapshot("ready", Pairing.validateSecret(fields[3]), port);
            } catch (IllegalArgumentException ignored) { }
        }
        throw unavailable();
    }

    static IOException unavailable() {
        return new IOException(text("无法通过 ADB 获取配对信息。请更新配套手机版，或输入手机显示的配对码。",
                "Cannot read pairing information over ADB. Update the matching phone app, or enter its pairing code."));
    }

    private final boolean automatic;
    private String secret;
    private int port;
    private String launchedDevice;

    AdbPairingSession(String requestedSecret, int requestedPort) {
        automatic = requestedSecret.isEmpty();
        secret = automatic ? ConnectionController.newPairingSecret() : requestedSecret;
        port = requestedPort;
    }

    /**
     * Re-read the current session on every retry, so a phone-side restart or port
     * change cannot leave us using an old code. An explicit code is never replaced.
     * False means the phone has not approved/started its service yet.
     */
    boolean prepare(Phone phone, String device, boolean launch) throws IOException, InterruptedException {
        if (automatic) {
            Snapshot snapshot = phone.readPairing(device);
            if ("busy".equals(snapshot.state)) throw new IOException(text(
                    "手机已连接另一会话，请先断开该连接。", "The phone is already connected. Disconnect that session first."));
            if ("ready".equals(snapshot.state)) {
                secret = snapshot.secret;
                port = snapshot.port;
                return true;
            }
        }
        if (launch && !device.equals(launchedDevice)) {
            phone.launchPhone(device, secret, port);
            launchedDevice = device;
        }
        return !automatic;
    }

    String secret() { return secret; }
    int port() { return port; }
}
