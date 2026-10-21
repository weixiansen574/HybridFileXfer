package top.weixiansen574.nio;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;

public class DataByteChannel implements ByteChannel, DataInput, DataOutput {
    private final ByteChannel origin;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8);
    private final ByteBuffer writeBuffer = ByteBuffer.allocate(8);

    public DataByteChannel(ByteChannel origin) {
        this.origin = origin;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return origin.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return origin.write(src);
    }

    public void writeFully(ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted write");
            if (origin.write(src) == 0) Thread.yield();
        }
    }

    @Override
    public boolean isOpen() {
        return origin.isOpen();
    }

    @Override
    public void close() throws IOException {
        origin.close();
    }

    public void readFully(ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted read");
            if (origin.read(dst) == -1) {
                throw new EOFException();
            }
        }
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        readFully(ByteBuffer.wrap(b));
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        readFully(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public int skipBytes(int n) throws IOException {
        ByteBuffer skipBuffer = ByteBuffer.allocate(n);
        return origin.read(skipBuffer);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        readBuffer.clear().limit(1);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.get();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readByte() & 0xFF;
    }

    @Override
    public short readShort() throws IOException {
        readBuffer.clear().limit(2);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public int readInt() throws IOException {
        readBuffer.clear().limit(4);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getInt();
    }

    @Override
    public long readLong() throws IOException {
        readBuffer.clear().limit(8);
        readFully(readBuffer);
        readBuffer.flip();
        return readBuffer.getLong();
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedOperationException("readLine() is not implemented.");
    }

    @Override
    public String readUTF() throws IOException {
        int length = readUnsignedShort();
        byte[] bytes = new byte[length];
        readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void write(int b) throws IOException {
        writeByte(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        writeFully(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        writeByte(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.put((byte) v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeShort(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putShort((short) v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putInt(v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeLong(long v) throws IOException {
        writeBuffer.clear();
        writeBuffer.putLong(v);
        writeBuffer.flip();
        writeFully(writeBuffer);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    @Override
    public void writeBytes(String s) throws IOException {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeChars(String s) throws IOException {
        for (char c : s.toCharArray()) {
            writeChar(c);
        }
    }

    @Override
    public void writeUTF(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("String too long");
        }
        writeShort(bytes.length);
        write(bytes);
    }
}
