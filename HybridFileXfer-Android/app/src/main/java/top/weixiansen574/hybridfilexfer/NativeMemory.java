package top.weixiansen574.hybridfilexfer;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 由于Java层有256M或512M的堆内存大小限制
 * ByteBuffer.allocate() 和 ByteBuffer.allocateDirect()
 * 都不可以获取共计超过256M或512M的内存。故用JNI分配内存并生成对应ByteBuffer。
 */
public class NativeMemory {
    static {
        System.loadLibrary("native-memory"); // 加载 JNI 库
    }

    public static native ByteBuffer allocateLargeBuffer(int size);
    public static native void freeBuffer(ByteBuffer buffer);

    /** 在不覆盖已存在目标文件的前提下，将暂存文件原子发布为目标路径。 */
    public static void moveNoReplace(String source, String target) throws IOException {
        if (source == null || target == null || source.indexOf('\0') >= 0 || target.indexOf('\0') >= 0) {
            throw new IOException("Invalid file path");
        }
        // 使用标准 UTF-8 字节数组传递路径，避免 JNI Modified UTF-8 导致生僻字或 Emoji 路径乱码。
        moveNoReplaceBytes(source.getBytes(StandardCharsets.UTF_8), target.getBytes(StandardCharsets.UTF_8));
    }

    private static native void moveNoReplaceBytes(byte[] source, byte[] target) throws IOException;
}
