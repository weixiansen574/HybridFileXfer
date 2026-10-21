package top.weixiansen574.hybridfilexfer.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** The GUI and CLI use the same validated connection settings. */
public final class LaunchOptions {
    public boolean gui, help, version, wait, burst, launchPhone;
    public String connect = "adb", device, directory = "/", secret = "";
    public int port = 5740, localPort = 0;

    public static LaunchOptions parse(String[] args) throws IOException {
        LaunchOptions result = new LaunchOptions();
        result.gui = args.length == 0;
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            int equals = arg.indexOf('=');
            String key = equals < 0 ? arg : arg.substring(0, equals);
            String value = equals < 0 ? null : arg.substring(equals + 1);
            switch (key) {
                case "-h": case "--help": case "-help": key = "help"; break;
                case "-v": case "--version": key = "version"; break;
                case "--gui": key = "gui"; break;
                case "--cli": key = "cli"; break;
                case "--wait": key = "wait"; break;
                case "--adb-burst": key = "burst"; break;
                case "--launch-phone": key = "launch"; break;
                case "-c": case "--connect": key = "connect"; break;
                case "-s": case "--device": case "--switch-device": key = "device"; break;
                case "-d": case "--dir": key = "dir"; break;
                case "-p": case "--port": key = "port"; break;
                case "--local-port": key = "local"; break;
                case "--pairing-code": key = "secret"; break;
                case "--pairing-code-file": key = "secretFile"; break;
                default: throw new IllegalArgumentException("Unknown argument: " + key + ". Use --help.");
            }
            boolean flag = key.equals("help") || key.equals("version") || key.equals("gui")
                    || key.equals("cli") || key.equals("wait") || key.equals("burst") || key.equals("launch");
            if (flag) {
                if (value != null) throw new IllegalArgumentException("This option takes no value: " + arg);
                value = "true";
            } else {
                if (value == null && i + 1 < args.length && !args[i + 1].startsWith("-")) value = args[++i];
                if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing value for " + arg);
            }
            if (values.put(key, value) != null) throw new IllegalArgumentException("Repeated option: " + key);
        }
        if (values.containsKey("gui") && values.containsKey("cli")) throw new IllegalArgumentException("--gui and --cli cannot be combined.");
        if (values.containsKey("secret") && values.containsKey("secretFile")) throw new IllegalArgumentException("Choose one pairing-code option.");
        result.gui |= values.containsKey("gui");
        result.help = values.containsKey("help");
        result.version = values.containsKey("version");
        result.wait = values.containsKey("wait");
        result.burst = values.containsKey("burst");
        result.launchPhone = values.containsKey("launch");
        result.connect = values.getOrDefault("connect", "adb");
        result.device = values.get("device");
        result.directory = values.getOrDefault("dir", "/");
        result.secret = values.getOrDefault("secret", "");
        if (values.containsKey("secretFile")) {
            java.nio.file.Path file = Paths.get(values.get("secretFile"));
            if (Files.size(file) > 4096) throw new IllegalArgumentException("Pairing code file is too large.");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 4096) throw new IllegalArgumentException("Pairing code file is too large.");
            result.secret = new String(bytes, StandardCharsets.UTF_8).trim();
        }
        result.port = parsePort(values.getOrDefault("port", "5740"), false);
        result.localPort = parsePort(values.getOrDefault("local", "0"), true);
        return result;
    }

    public static int parsePort(String value, boolean allowZero) {
        try {
            int port = Integer.parseInt(value);
            if (port >= (allowZero ? 0 : 1) && port <= 65535) return port;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Port must be " + (allowZero ? "0" : "1") + "–65535.");
    }
}
