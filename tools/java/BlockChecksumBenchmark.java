import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Isolates CRC CPU/memory cost on existing heap buffers; excludes file I/O and networking.
 * Explicit manual benchmark, not a timing assertion or a device-throughput claim.
 */
public final class BlockChecksumBenchmark {
    private static volatile long result;

    private static double measure(byte[][] blocks, long nanos) {
        CRC32 crc = new CRC32();
        long start = System.nanoTime(), count = 0, sum = 0;
        do {
            for (byte[] block : blocks) {
                crc.reset();
                crc.update(block, 0, block.length);
                sum ^= crc.getValue();
                count++;
            }
        } while (System.nanoTime() - start < nanos);
        long elapsed = System.nanoTime() - start;
        result = sum;
        return count * (double) blocks[0].length / (1024 * 1024) / (elapsed / 1e9);
    }

    public static void main(String[] args) {
        // 64 MiB working set, using the protocol's 1 MiB blocks without copying.
        byte[][] blocks = new byte[64][1024 * 1024];
        Random random = new Random(574);
        for (byte[] block : blocks) random.nextBytes(block);
        for (int i = 0; i < 3; i++) measure(blocks, 500_000_000L);
        double[] samples = new double[7];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = measure(blocks, 500_000_000L);
            System.out.printf(Locale.ROOT, "sample=%d CRC32_MiB_per_second=%.2f%n", i + 1, samples[i]);
        }
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "median_CRC32_MiB_per_second=%.2f%n", samples[3]);
        System.out.println("One checksum per block per endpoint; no disk, copying, encryption or network included.");
        System.out.println("Runtime: " + System.getProperty("java.version") + " " + System.getProperty("os.arch"));
    }
}
