package top.weixiansen574.hybridfilexfer.desktop;

import top.weixiansen574.hybridfilexfer.Strings;
import top.weixiansen574.hybridfilexfer.core.TransferOptions;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class DesktopRegressionTest {
    public static void main(String[] args) throws Exception {
        assert LaunchOptions.parse(new String[0]).gui;
        LaunchOptions cli = LaunchOptions.parse(new String[]{"--cli", "--connect=adb", "--device", "device-a",
                "-d", "C:\\folder with spaces", "--port", "5701", "--local-port", "0", "--wait", "--adb-burst"});
        assert !cli.gui && cli.wait && cli.burst && cli.port == 5701 && cli.localPort == 0;
        assert cli.directory.equals("C:\\folder with spaces") && cli.device.equals("device-a");
        assert LaunchOptions.parse(new String[]{"-help"}).help;
        assert LaunchOptions.parse(new String[]{"--version"}).version;
        expectInvalid("--port", "0"); expectInvalid("--port=65536"); expectInvalid("--local-port=-1");
        expectInvalid("--unknown"); expectInvalid("--connect"); expectInvalid("--help=value");
        expectInvalid("adb"); expectInvalid("--gui", "--cli"); expectInvalid("--port", "1", "--port", "2");
        Path temporary = Files.createTempDirectory("hfx-desktop-test-");
        Path secret = temporary.resolve("pairing.txt");
        Files.write(secret, "abc_DEF-123456\n".getBytes(StandardCharsets.UTF_8));
        assert LaunchOptions.parse(new String[]{"--pairing-code-file", secret.toString()}).secret.equals("abc_DEF-123456");
        assert !Strings.bundleFor(new Locale("zh", "SG")).getString("connect_success").isEmpty();
        assert Strings.bundleFor(Locale.FRANCE).getString("connect_success").equals("Connection complete.");
        assert Strings.bundleFor(new Locale("en", "NZ")).getString("connect_success").equals("Connection complete.");
        assert !Strings.bundleFor(Locale.forLanguageTag("zh-Hant-HK")).getString("connect_success").isEmpty();
        List<AdbBridge.Device> devices = AdbBridge.parseDevices("List of devices attached\nabc device product:x model:Pixel_8 transport_id:1\n"
                + "def unauthorized\nxyz offline\n* daemon started successfully *\n");
        assert devices.size() == 3 && devices.get(0).name.equals("Pixel 8");
        assert ConnectionController.selectDevice(devices, null).serial.equals("abc");
        try { ConnectionController.selectDevice(devices, "def"); throw new AssertionError("Unauthorized device selected"); } catch (java.io.IOException expected) { }
        try { ConnectionController.selectDevice(Arrays.asList(devices.get(0), new AdbBridge.Device("two", "device", "Two")), null);
            throw new AssertionError("Ambiguous device selected"); } catch (java.io.IOException expected) { }
        assert ConnectionController.retryDelaySeconds(0) == 1;
        assert ConnectionController.retryDelaySeconds(1) == 2;
        assert ConnectionController.retryDelaySeconds(1000) == 15;
        assert ConnectionController.newPairingSecret().matches("[A-Za-z0-9_-]{32}");
        cancellationAndReuse(temporary);
        System.setProperty("hfx.dataDir", temporary.toString());
        TransferHistory store = new TransferHistory();
        store.save(Arrays.asList(new String[][]{{"2026-01-01", "Upload", "completed\tverified", "1 MiB", "0:00:01"}}));
        assert store.load().size() == 1 && store.load().get(0)[2].equals("completed verified");
        System.out.println("Desktop regression checks passed: arguments, locale fallback, device selection, bounded retry, cancellation/reuse, history.");
    }
    private static void expectInvalid(String... args) throws Exception {
        try { LaunchOptions.parse(args); throw new AssertionError("Accepted invalid arguments " + Arrays.toString(args)); }
        catch (IllegalArgumentException expected) { }
    }
    private static void cancellationAndReuse(Path directory) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    release.await(10, TimeUnit.SECONDS);
                } catch (Throwable e) { failure.set(e); }
            }, "test-silent-peer");
            peer.setDaemon(true);
            peer.start();
            ConnectionController controller = new ConnectionController(new ConnectionController.Listener() { });
            ConnectionController.Settings settings = new ConnectionController.Settings("127.0.0.1", null,
                    directory.toString(), "unit_test_secret_12345678", server.getLocalPort(), 0,
                    true, false, false, new TransferOptions());
            controller.start(settings);
            if (!accepted.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test peer never received connection");
            long start = System.nanoTime();
            controller.close();
            assert controller.await(3, TimeUnit.SECONDS) : "Cancel failed to unblock the handshake";
            assert TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000;
            release.countDown(); peer.join(3000);
            assert failure.get() == null : failure.get();
            // A completed/cancelled controller accepts a new connection request without a restart.
            controller.start(settings); controller.close();
            assert controller.await(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
}
