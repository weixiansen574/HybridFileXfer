package top.weixiansen574.hybridfilexfer;

import java.nio.ByteBuffer;

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
}
