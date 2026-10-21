package top.weixiansen574.hybridfilexfer.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Export the author's original logo, shared with the actual application window. */
public final class DesktopIconExporter {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        for (int size : new int[]{16, 32, 48, 256, 512}) {
            ImageIO.write(DesktopTheme.applicationIcon(size), "png",
                    output.resolve("HybridFileXfer-" + size + ".png").toFile());
        }
        writeWindowsIcon(output);
    }

    private static void writeWindowsIcon(Path output) throws Exception {
        int[] sizes = {16, 32, 48, 256};
        byte[][] images = new byte[sizes.length][];
        int length = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage image = DesktopTheme.applicationIcon(sizes[i]);
            if (sizes[i] == 256) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                images[i] = png.toByteArray();
            } else {
                images[i] = windowsBitmap(image);
            }
            length += images[i].length;
        }
        ByteBuffer icon = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        icon.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            icon.put((byte) sizes[i]).put((byte) sizes[i]).put((byte) 0).put((byte) 0);
            icon.putShort((short) 1).putShort((short) 32);
            icon.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] image : images) icon.put(image);
        Files.write(output.resolve("HybridFileXfer.ico"), icon.array());
    }

    /** Classic BGRA DIB frames serve Explorer's small/medium/large icon views. */
    private static byte[] windowsBitmap(BufferedImage image) {
        int size = image.getWidth();
        int maskStride = ((size + 31) / 32) * 4;
        int pixelBytes = size * size * 4, maskBytes = maskStride * size;
        ByteBuffer data = ByteBuffer.allocate(40 + pixelBytes + maskBytes).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(40).putInt(size).putInt(size * 2);
        data.putShort((short) 1).putShort((short) 32);
        data.putInt(0).putInt(pixelBytes + maskBytes);
        data.putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = size - 1; y >= 0; y--)
            for (int x = 0; x < size; x++) data.putInt(image.getRGB(x, y));
        byte[] mask = new byte[maskBytes];
        for (int y = size - 1; y >= 0; y--)
            for (int x = 0; x < size; x++)
                if ((image.getRGB(x, y) >>> 24) == 0)
                    mask[(size - 1 - y) * maskStride + x / 8] |= (byte) (0x80 >>> (x % 8));
        data.put(mask);
        return data.array();
    }
}
