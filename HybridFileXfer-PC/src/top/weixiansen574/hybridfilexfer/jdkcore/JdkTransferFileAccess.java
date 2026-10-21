package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import top.weixiansen574.hybridfilexfer.core.LocalTransferFileAccess;

public class JdkTransferFileAccess extends LocalTransferFileAccess {
    @Override protected String reservationKey(File file) throws IOException {
        String path=super.reservationKey(file);
        // A missing Windows destination still has case-insensitive identity.
        return File.separatorChar=='\\'?path.toLowerCase(java.util.Locale.ROOT):path;
    }
    @Override protected String fileIdentity(File file) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes=Files.readAttributes(file.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Object key=attributes.fileKey();
        // The Windows JDK provider returns null for fileKey. Its high-resolution
        // creation/change stamps detect ordinary replacement while our target
        // reservation excludes other cooperating transfer sessions. They are not
        // a security boundary against an external writer forging timestamps.
        return key!=null?key.toString():"timestamps:"+attributes.creationTime()+":"+attributes.lastModifiedTime();
    }
    @Override protected boolean sameFile(File first,File second) throws IOException {
        return Files.isSameFile(first.toPath(),second.toPath());
    }
    @Override protected File physicalPath(File file) throws IOException {
        java.nio.file.Path path=file.toPath().toAbsolutePath().normalize();
        java.util.ArrayDeque<String> tail=new java.util.ArrayDeque<>();
        while (!Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (path.getParent()==null) throw new IOException("Cannot resolve filesystem root");
            tail.addFirst(path.getFileName().toString());
            path=path.getParent();
        }
        path=path.toRealPath();
        for (String name:tail) path=path.resolve(name);
        return path.toFile();
    }
    @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
        if (replace) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            // ATOMIC_MOVE is allowed to overwrite an existing target even without REPLACE_EXISTING.
            if (File.separatorChar == '\\') {
                // Windows MoveFileEx without REPLACE_EXISTING also supports FAT/exFAT.
                Files.move(source.toPath(), target.toPath());
            } else {
                Files.createLink(target.toPath(), source.toPath());
                try { Files.delete(source.toPath()); }
                catch (IOException | SecurityException cleanupFailure) {
                    // The target has already been published. The caller reports the
                    // leftover part as a warning and prevents it being truncated later.
                }
            }
        }
    }
}
