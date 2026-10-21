package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import top.weixiansen574.hybridfilexfer.core.LocalTransferFileAccess;

public class JdkTransferFileAccess extends LocalTransferFileAccess {
    @Override protected String reservationKey(File file) throws IOException {
        String path=super.reservationKey(file);
        // Windows 下目标文件尚未创建时，按不区分大小写的规范化路径生成标识。
        return File.separatorChar=='\\'?path.toLowerCase(java.util.Locale.ROOT):path;
    }
    @Override protected String fileIdentity(File file) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attributes=Files.readAttributes(file.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Object key=attributes.fileKey();
        // Windows 下 BasicFileAttributes.fileKey() 返回 null，改用创建时间、修改时间、大小与规范路径组合标识文件。
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
            // Java NIO 的 ATOMIC_MOVE 在部分平台上即使未传 REPLACE_EXISTING 也会静默覆盖目标文件。
            if (File.separatorChar == '\\') {
                // Windows 下不带 REPLACE_EXISTING 的普通 move 即底层 MoveFileEx，可保证不覆盖已有目标并兼容 FAT/exFAT。
                Files.move(source.toPath(), target.toPath());
            } else {
                Files.createLink(target.toPath(), source.toPath());
                try { Files.delete(source.toPath()); }
                catch (IOException | SecurityException cleanupFailure) {
                    // 硬链接已成功创建目标文件，若源 .part 删除失败则抛出异常由上层记录清理提示。
                }
            }
        }
    }
}
