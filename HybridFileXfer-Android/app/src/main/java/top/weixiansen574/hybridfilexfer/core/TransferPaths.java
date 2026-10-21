package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;

/** Portable lossless escaping; a:b and a?b never silently alias the same target. */
public final class TransferPaths {
    private final Map<String,String> assigned = new HashMap<>();
    private final Map<String,String> owners = new HashMap<>();

    public String map(String source, Directory sourceRoot) throws IOException {
        String separator = sourceRoot.fileSystem == Directory.FILE_SYSTEM_WINDOWS ? "\\" : "/";
        String root = sourceRoot.path;
        String path = sourceRoot.fileSystem == Directory.FILE_SYSTEM_WINDOWS ? source.replace('/', '\\') : source;
        boolean inside = sourceRoot.fileSystem == Directory.FILE_SYSTEM_WINDOWS
                ? path.toLowerCase(Locale.ROOT).startsWith(root.toLowerCase(Locale.ROOT)) : path.startsWith(root);
        if (!inside) throw new IOException("Source is outside the selected directory: " + source);
        String relative = path.substring(root.length());
        String[] segments = relative.split(java.util.regex.Pattern.quote(separator), -1);
        String original = "", mapped = "";
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
                throw new IOException("Unsafe source path component");
            original += "/" + segment;
            String chosen = assigned.get(original);
            if (chosen == null) {
                String encoded = encode(segment);
                chosen = mapped.isEmpty() ? encoded : mapped + "/" + encoded;
                String key = chosen.toLowerCase(Locale.ROOT);
                if (owners.containsKey(key) && !owners.get(key).equals(original)) {
                    encoded += "~" + TransferHashes.text(original).substring(0, 12);
                    chosen = mapped.isEmpty() ? encoded : mapped + "/" + encoded;
                    key = chosen.toLowerCase(Locale.ROOT);
                    if (owners.containsKey(key)) throw new IOException("Destination name collision");
                }
                owners.put(key, original);
                assigned.put(original, chosen);
            }
            mapped = chosen;
        }
        validateRelative(mapped);
        return mapped;
    }

    public static String encode(String name) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean escape = c < 32 || c == 127 || "\\/:*?\"<>|%".indexOf(c) >= 0
                    || (i == name.length() - 1 && (c == ' ' || c == '.'));
            if (escape) result.append(String.format(Locale.ROOT, "%%%02X", (int)c));
            else result.append(c);
        }
        String encoded = result.toString();
        String stem = encoded.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) encoded = "%5F" + encoded;
        // Leave space for collision suffixes and filesystem limits, fail clearly instead of truncating.
        if (encoded.isEmpty() || encoded.getBytes(StandardCharsets.UTF_8).length > 240)
            throw new IOException("File name is too long after portable escaping");
        return encoded;
    }

    public static void validateRelative(String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 16384
                || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0)
            throw new IOException("Unsafe destination path");
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IOException("Unsafe destination name");
            for (int i=0; i<segment.length(); i++)
                if (segment.charAt(i)<32 || segment.charAt(i)==127 || "*?\"<>|".indexOf(segment.charAt(i))>=0)
                    throw new IOException("Unsafe destination character");
            String stem = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
                throw new IOException("Reserved destination name");
        }
    }
}
