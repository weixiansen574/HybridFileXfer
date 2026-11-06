package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;

public class FileBlock implements Comparable<FileBlock> {
    /**
     * 单个数据块大小。
     * <p>
     * 该值参与接收端的落盘偏移计算（{@code index * BLOCK_SIZE}），
     * 收发两端必须一致，改动它等于破坏协议兼容性。
     */
    public static final int BLOCK_SIZE = 2 * 1024 * 1024; // 2MB

    public final boolean isFile;
    public final int fileIndex;
    /** 目标路径：数据最终落在对端磁盘上的位置，会随块头部发给对端。绝不用于打开源文件。 */
    public final String path;
    /** 源文件的真实路径（仅发送侧使用，不参与协议）。用它打开 sendfile 的源句柄。 */
    public final String sourcePath;
    public final long lastModified;
    public final long totalSize;
    public final int index;

    /** 接收侧：承载网络数据的缓冲区；发送侧恒为 null（零拷贝，数据不落用户内存） */
    public final ByteBuffer data;

    /** 块内有效数据长度。显式保存，不再依赖 ByteBuffer.position() */
    public final int length;

    /** 发送侧：该块在源文件中的起始偏移；接收侧恒为 index * BLOCK_SIZE */
    public final long fileOffset;

    /**
     * 接收侧 / 目录条目构造器。
     * 数据长度取自 {@code data.position()}，保持原有语义。
     */
    public FileBlock(boolean isFile, int fileIndex, String path, long lastModified, long totalSize, int index, ByteBuffer data) {
        this.isFile = isFile;
        this.fileIndex = fileIndex;
        this.path = path;
        this.sourcePath = null;
        this.lastModified = lastModified;
        this.totalSize = totalSize;
        this.index = index;
        this.data = data;
        this.length = data == null ? 0 : data.position();
        this.fileOffset = BLOCK_SIZE * (long) index;
    }

    /**
     * 发送侧零拷贝构造器：只描述“源文件的哪一段”，不携带任何数据。
     *
     * @param path       目标路径（写给对端）
     * @param sourcePath 源文件真实路径（本地打开用），与 path 绝不可混用
     */
    public FileBlock(int fileIndex, String path, String sourcePath, long lastModified, long totalSize, int index, int length) {
        this.isFile = true;
        this.fileIndex = fileIndex;
        this.path = path;
        this.sourcePath = sourcePath;
        this.lastModified = lastModified;
        this.totalSize = totalSize;
        this.index = index;
        this.data = null;
        this.length = length;
        this.fileOffset = BLOCK_SIZE * (long) index;
    }

    public long getStartPosition(){
        return  BLOCK_SIZE * ((long) index);
    }

    public long calcBlockCount(){
        return totalSize / BLOCK_SIZE + 1;
    }

    public boolean isFile(){
        return isFile;
    }

    public boolean isDirectory(){
        return !isFile;
    }

    public int getLength(){
        return length;
    }

    @Override
    public int compareTo(FileBlock other) {
        if (this.fileIndex != other.fileIndex) {
            return Integer.compare(this.fileIndex, other.fileIndex);
        }
        // 如果 nameIndex 相同，比较 index
        return Integer.compare(this.index, other.index);
    }
}
