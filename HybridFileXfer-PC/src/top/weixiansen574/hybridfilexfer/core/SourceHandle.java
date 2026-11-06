package top.weixiansen574.hybridfilexfer.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * 发送侧只读源文件句柄。
 * <p>
 * {@link #channel()} 仅供 {@link FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}
 * 使用：transferTo 走显式 position 访问，不修改 fd 偏移，因此同一个 channel
 * 可以被多个发送线程并发调用（底层 sendfile 不依赖 fd 的当前位置）。
 * <p>
 * 注意：sendfile 为零拷贝路径，数据不经过用户内存，所以不要再从这个 channel
 * 上做 read()，否则会破坏“零拷贝”并让 fd 偏移与 transferTo 的 position 打架。
 */
public abstract class SourceHandle implements Closeable {

    public abstract FileChannel channel();

    @Override
    public abstract void close() throws IOException;
}
