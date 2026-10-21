package top.weixiansen574.hybridfilexfer.desktop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns only the processes and port forwards created by this connection attempt. */
public final class AdbBridge implements AutoCloseable, AdbPairingSession.Phone {
    public static final class Device {
        public final String serial, state, name;
        public Device(String serial, String state, String name) {
            this.serial = serial; this.state = state; this.name = name;
        }
        public boolean ready() { return "device".equals(state); }
        @Override public String toString() { return name + " · " + serial + (ready() ? "" : " [" + state + "]"); }
    }
    private final boolean burst;
    private final String executable;
    private volatile Process running;
    private volatile boolean closed;
    private int forwardPort;
    private String forwardDevice;

    public AdbBridge(boolean burst) {
        this.burst = burst;
        String filename = System.getProperty("os.name", "").toLowerCase().contains("win") ? "adb.exe" : "adb";
        Path base;
        try {
            File code = new File(AdbBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            base = (code.isDirectory() ? code : code.getParentFile()).toPath();
        } catch (Exception ignored) { base = Paths.get(".").toAbsolutePath(); }
        File bundled = base.resolve(filename).toFile();
        File platformTools = base.resolve("platform-tools").resolve(filename).toFile();
        executable = bundled.isFile() ? bundled.getAbsolutePath()
                : platformTools.isFile() ? platformTools.getAbsolutePath() : filename;
    }

    public List<Device> devices() throws IOException, InterruptedException {
        return parseDevices(run(10000, "devices", "-l"));
    }

    public static List<Device> parseDevices(String output) {
        List<Device> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 2) continue;
            if (!Arrays.asList("device", "unauthorized", "offline", "recovery", "sideload", "no").contains(fields[1])) continue;
            String name = fields[0];
            for (String field : fields) if (field.startsWith("model:")) name = field.substring(6).replace('_', ' ');
            devices.add(new Device(fields[0], fields[1], name));
        }
        return devices;
    }

    public int forward(String device, int requestedLocalPort, int remotePort) throws IOException, InterruptedException {
        List<String> args = deviceArgs(device);
        args.addAll(Arrays.asList("forward", "--no-rebind", "tcp:" + requestedLocalPort, "tcp:" + remotePort));
        String output = run(10000, args.toArray(new String[0])).trim();
        int allocated = requestedLocalPort;
        if (allocated == 0) {
            try { allocated = Integer.parseInt(output); }
            catch (NumberFormatException e) { throw new IOException("ADB did not return the allocated port. Update Android platform-tools."); }
            if (allocated < 1 || allocated > 65535) throw new IOException("Invalid port returned by ADB.");
        }
        forwardDevice = device;
        forwardPort = allocated;
        return allocated;
    }

    @Override public AdbPairingSession.Snapshot readPairing(String device) throws IOException, InterruptedException {
        List<String> args = deviceArgs(device);
        args.addAll(Arrays.asList("shell", "content", "call", "--uri",
                "content://top.weixiansen574.hybridfilexfer.adb-pairing", "--method", "current"));
        String output;
        try { output = run(10000, args.toArray(new String[0])); }
        catch (IOException error) {
            // A failing adb/content process may echo a Bundle containing the code.
            throw AdbPairingSession.unavailable();
        }
        return AdbPairingSession.parse(output);
    }

    public void launchPhone(String device, String secret, int serverPort) throws IOException, InterruptedException {
        List<String> args = deviceArgs(device);
        args.addAll(Arrays.asList("shell", "am", "start", "-n",
                "top.weixiansen574.hybridfilexfer/.MainActivity", "-a",
                "top.weixiansen574.hybridfilexfer.action.REQUEST_START",
                "--es", "pairing_secret", secret, "--es", "request_id", UUID.randomUUID().toString(),
                "--ei", "server_port", String.valueOf(serverPort)));
        // These extras cross adb shell: pairing secrets deliberately have no shell metacharacters.
        if (!secret.matches("[A-Za-z0-9_-]{6,128}")) throw new IOException("Pairing code must contain 6–128 letters, digits, '_' or '-'.");
        String output;
        try { output = run(10000, args.toArray(new String[0])); }
        catch (IOException error) {
            throw new IOException("Cannot open the phone app. Check USB authorization and install the matching Android version.");
        }
        if (output.contains("Error:") || output.contains("Exception")) throw new IOException("Cannot open the phone app. Install the matching Android version.");
    }

    private List<String> deviceArgs(String device) {
        List<String> args = new ArrayList<>();
        if (device != null && !device.isEmpty()) args.addAll(Arrays.asList("-s", device));
        return args;
    }

    private String run(long timeoutMs, String... args) throws IOException, InterruptedException {
        if (closed) throw new InterruptedException("ADB operation cancelled");
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (burst) builder.environment().put("ADB_BURST_MODE", "1");
        Process process;
        try { process = builder.start(); }
        catch (IOException e) { throw new IOException("ADB not found. Install platform-tools or place adb beside the application.", e); }
        running = process;
        if (closed) process.destroyForcibly();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] block = new byte[4096];
                int count;
                while ((count = input.read(block)) >= 0) {
                    synchronized (output) {
                        int room = 65536 - output.size();
                        if (room > 0) output.write(block, 0, Math.min(room, count));
                    }
                }
            } catch (IOException ignored) { }
        }, "hfx-adb-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("ADB timed out. Check the USB connection and phone authorization.");
            }
            reader.join(1000);
            String text;
            synchronized (output) { text = new String(output.toByteArray(), StandardCharsets.UTF_8); }
            if (process.exitValue() != 0) throw new IOException("ADB: " + text.trim());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            if (running == process) running = null;
        }
    }

    @Override public void close() {
        closed = true;
        Process process = running;
        if (process != null) process.destroyForcibly();
    }

    /** Called by the worker after cancellation, never on the Swing event thread. */
    public void removeForward() {
        if (forwardPort == 0) return;
        boolean interrupted = Thread.interrupted();
        try (AdbBridge cleanup = new AdbBridge(burst)) {
            List<String> args = cleanup.deviceArgs(forwardDevice);
            args.addAll(Arrays.asList("forward", "--remove", "tcp:" + forwardPort));
            cleanup.run(3000, args.toArray(new String[0]));
        } catch (Exception ignored) { }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        forwardPort = 0;
    }
}
