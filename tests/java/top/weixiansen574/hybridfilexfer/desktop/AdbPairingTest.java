package top.weixiansen574.hybridfilexfer.desktop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import top.weixiansen574.hybridfilexfer.security.Pairing;
import top.weixiansen574.hybridfilexfer.security.HandshakeDeadline;
import top.weixiansen574.nio.DataByteChannel;

/** Both startup orders and code rotation, including a real authenticated socket. */
public final class AdbPairingTest {
    private static final String FIRST = "PHONE_FIRST_CODE_1234";
    private static final String SECOND = "NEW_PHONE_SESSION_5678";
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static final class Phone implements AdbPairingSession.Phone {
        String state = "idle", secret = FIRST, proposed;
        int port = 6812, launches, reads;
        @Override public AdbPairingSession.Snapshot readPairing(String device) throws IOException {
            check("selected-phone".equals(device), "Lookup must target the selected phone");
            reads++;
            return snapshot(state.equals("ready") ? "ready:" + port + ":" + secret : state);
        }
        @Override public void launchPhone(String device, String secret, int port) {
            check("selected-phone".equals(device), "Launch must target the selected phone");
            launches++;
            proposed = secret;
            this.port = port;
        }
        void approve() { secret = proposed; state = "ready"; }
    }
    private static AdbPairingSession.Snapshot snapshot(String payload) throws IOException {
        return AdbPairingSession.parse("Result: Bundle[{hfx_pairing=HFXPAIR1:" + payload + "}]\r\n");
    }
    public static void main(String[] args) throws Exception {
        Phone phone = new Phone();
        phone.state = "ready";
        AdbPairingSession automatic = new AdbPairingSession("", 5740);
        check(automatic.prepare(phone, "selected-phone", true), "Phone-first session is ready");
        check(automatic.secret().equals(FIRST) && automatic.port() == 6812, "Use phone's existing code and port");
        check(phone.launches == 0, "Phone-first must preserve the running service");
        authenticatedRoundTrip(automatic.secret(), phone.secret);
        phone.secret = SECOND;
        phone.port = 6913;
        check(automatic.prepare(phone, "selected-phone", false), "Refresh pairing even with launching disabled");
        check(automatic.secret().equals(SECOND) && automatic.port() == 6913, "Retry adopts restarted phone's session");
        authenticatedRoundTrip(automatic.secret(), phone.secret);
        phone.state = "busy";
        try { automatic.prepare(phone, "selected-phone", true); throw new AssertionError("Busy session reused"); }
        catch (IOException expected) { checks++; }
        check(snapshot("busy").secret.isEmpty(), "Busy reply contains no code");

        Phone desktopFirst = new Phone();
        AdbPairingSession requested = new AdbPairingSession("", 5749);
        check(!requested.prepare(desktopFirst, "selected-phone", true), "Desktop-first waits for approval");
        check(desktopFirst.launches == 1 && desktopFirst.proposed.equals(requested.secret()), "Propose one stable code");
        check(!requested.prepare(desktopFirst, "selected-phone", true), "Still wait before approval");
        check(desktopFirst.launches == 1, "Retry must not replace the phone's confirmation dialog");
        desktopFirst.approve();
        check(requested.prepare(desktopFirst, "selected-phone", true), "Phone approval enables pairing");
        authenticatedRoundTrip(requested.secret(), desktopFirst.secret);

        Phone manualPhone = new Phone();
        AdbPairingSession manual = new AdbPairingSession(FIRST, 5740);
        check(manual.prepare(manualPhone, "selected-phone", false), "Manual mode does not require the provider");
        check(manualPhone.reads == 0 && manualPhone.launches == 0 && manual.secret().equals(FIRST), "Preserve explicit codes");
        check(manual.prepare(manualPhone, "selected-phone", true) && manualPhone.launches == 1, "Explicit launch still works");
        Phone idle = new Phone();
        check(!new AdbPairingSession("", 5740).prepare(idle, "selected-phone", false), "No unsolicited launch");
        check(idle.launches == 0, "Launch setting respected");

        for (String malformed : new String[]{"ready:0:" + FIRST, "ready:65536:" + FIRST,
                "ready:5740:short", "ready:5740:" + FIRST + ":extra", "idle:" + FIRST, "unknown",
                "ready:5740:abc;echo_secret"}) {
            try { snapshot(malformed); throw new AssertionError("Accepted invalid provider response"); }
            catch (IOException expected) { check(!expected.toString().contains(FIRST), "Parse errors never echo secrets"); }
        }
        try { AdbPairingSession.parse("provider failed: " + FIRST); throw new AssertionError("Accepted failed provider"); }
        catch (IOException expected) { check(!expected.toString().contains(FIRST), "Provider errors never echo output"); }
        new ConnectionController.Settings("adb", null, "/", "", 5740, 0, true, false, false, new TransferOptions()).validate();
        try {
            new ConnectionController.Settings("127.0.0.1", null, "/", "", 5740, 0, true, false, false, new TransferOptions()).validate();
            throw new AssertionError("Network pairing allowed without a code");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("ADB pairing checks passed: " + checks);
    }

    private static void authenticatedRoundTrip(String selected, String phoneCode) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                 SocketChannel server = listener.accept();
                 HandshakeDeadline clientDeadline = new HandshakeDeadline(client, 5000);
                 HandshakeDeadline serverDeadline = new HandshakeDeadline(server, 5000)) {
                // Bound both ends so a regression fails instead of hanging the build.
                DataByteChannel local = new DataByteChannel(client);
                DataByteChannel remote = new DataByteChannel(server);
                Future<Pairing.Session> accepted = worker.submit(() -> Pairing.server(remote, phoneCode));
                Pairing.Session paired = Pairing.client(local, selected);
                Pairing.Session peer = accepted.get(5, TimeUnit.SECONDS);
                paired.channel.writeUTF("authenticated");
                check(peer.channel.readUTF().equals("authenticated"), "Selected pairing code authenticates encrypted traffic");
            }
        } finally { worker.shutdownNow(); }
    }
}
