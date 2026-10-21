package top.weixiansen574.hybridfilexfer;

import org.junit.Test;
import top.weixiansen574.hybridfilexfer.core.FileBlock;

import static org.junit.Assert.*;

public class ChannelTaskStateTest {
    private static final long BLOCK_SIZE = FileBlock.BLOCK_SIZE;

    @Test public void recordsActualAssignedRangeAndDirection() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        assertTrue(channel.task("synthetic/first.bin", true, 1048576, 524288, 4194304));
        assertEquals(ChannelTaskState.Kind.TASK, channel.kind());
        assertEquals("synthetic/first.bin", channel.task().path);
        assertTrue(channel.task().upload);
        assertEquals(1048576, channel.task().offset);
        assertEquals(524288, channel.task().length);
        assertEquals(2, channel.task().blockNumber());
        assertEquals(4, channel.task().blockCount());
        assertEquals(4194304, channel.task().fileSize);
        assertTrue(channel.task("synthetic/second.bin", false, 0, 8, 8));
        assertFalse(channel.task().upload);
        assertEquals("synthetic/second.bin", channel.task().path);
    }

    @Test public void idleClearsOnlyTheActiveTask() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertTrue(channel.idle());
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertNull(channel.task());
        assertFalse(channel.idle());
        assertTrue(channel.task("synthetic/next.bin", true, 8, 8, 16));
    }

    @Test public void completedStateSurvivesLateIdleAndTasks() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertTrue(channel.complete("completed"));
        assertNull(channel.task());
        assertFalse(channel.idle());
        assertFalse(channel.task("synthetic/late.bin", true, 0, 8, 8));
        channel.end();
        assertEquals(ChannelTaskState.Kind.COMPLETE, channel.kind());
        assertEquals("completed", channel.message());
        assertFalse(channel.error("late error"));
    }

    @Test public void failedChannelRemainsFailedWhileOtherChannelsContinue() {
        ChannelTaskState failed = new ChannelTaskState();
        ChannelTaskState healthy = new ChannelTaskState();
        failed.begin();
        healthy.begin();
        failed.task("synthetic/file.bin", true, 0, 8, 16);
        healthy.task("synthetic/file.bin", true, 8, 8, 16);
        assertTrue(failed.error("channel disconnected"));
        assertNull(failed.task());
        assertFalse(failed.idle());
        assertFalse(failed.complete("completed"));
        assertFalse(failed.task("synthetic/late.bin", true, 0, 8, 8));
        assertEquals(ChannelTaskState.Kind.ERROR, failed.kind());
        assertEquals("channel disconnected", failed.message());
        assertEquals(ChannelTaskState.Kind.TASK, healthy.kind());
        assertTrue(healthy.task("synthetic/retry.bin", true, 0, 8, 16));
    }

    @Test public void cancelStopAndFinishRejectQueuedActivityUntilNextTransfer() {
        ChannelTaskState channel = new ChannelTaskState();
        assertFalse(channel.task("synthetic/before.bin", true, 0, 8, 8));
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        channel.end();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertNull(channel.task());
        assertFalse(channel.task("synthetic/late.bin", true, 0, 8, 8));
        assertFalse(channel.idle());
        assertFalse(channel.complete("late completion"));
        assertFalse(channel.error("late error"));
        channel.begin();
        assertTrue(channel.task("synthetic/new-transfer.bin", false, 8, 8, 16));
        assertEquals("synthetic/new-transfer.bin", channel.task().path);
    }

    @Test public void nextClientTransferResetsCompletionAndFailure() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.complete("first transfer complete");
        channel.end();
        channel.begin();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertEquals("", channel.message());
        assertTrue(channel.task("synthetic/second.bin", false, 0, 8, 8));
        channel.error("second transfer failed");
        channel.end();
        channel.begin();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertEquals("", channel.message());
        assertTrue(channel.task("synthetic/third.bin", true, 0, 8, 8));
    }

    @Test public void resumedLargeFilesKeepLongOffsetsWithoutOverflow() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        long offset = 5L * 1024 * 1024 * 1024 * 1024;
        assertTrue(channel.task("synthetic/large.bin", false, offset, 16, offset + 16));
        assertEquals(offset, channel.task().offset);
        assertEquals(16, channel.task().length);
        assertEquals(offset / BLOCK_SIZE + 1, channel.task().blockNumber());
        assertEquals(offset / BLOCK_SIZE + 1, channel.task().blockCount());
        assertTrue(channel.task("synthetic/limit.bin", true, Long.MAX_VALUE - 1, 1, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, channel.task().offset);
        assertEquals(1, channel.task().length);
        assertEquals(Long.MAX_VALUE / BLOCK_SIZE + 1, channel.task().blockNumber());
        assertEquals(Long.MAX_VALUE / BLOCK_SIZE + 1, channel.task().blockCount());
        assertFalse(channel.task("synthetic/overflow.bin", true, Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertEquals("synthetic/limit.bin", channel.task().path);
    }

    @Test public void invalidRangesDoNotReplaceDisplayedTask() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertFalse(channel.task(null, true, 0, 8, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, -1, 8, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 0, -1, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 0, 8, -1));
        assertFalse(channel.task("synthetic/invalid.bin", true, 9, 0, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 4, 8, 8));
        assertEquals("synthetic/file.bin", channel.task().path);
    }

    @Test public void blockCountsHandleExactMultiplesShortTailsAndEmptyFiles() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        assertTrue(channel.task("synthetic/exact.bin", true, 0, BLOCK_SIZE, 2 * BLOCK_SIZE));
        assertEquals(1, channel.task().blockNumber());
        assertEquals(2, channel.task().blockCount());
        assertTrue(channel.task("synthetic/exact.bin", true, BLOCK_SIZE, BLOCK_SIZE, 2 * BLOCK_SIZE));
        assertEquals(2, channel.task().blockNumber());
        assertEquals(2, channel.task().blockCount());
        assertTrue(channel.task("synthetic/tail.bin", false, 2 * BLOCK_SIZE, 17, 2 * BLOCK_SIZE + 17));
        assertEquals(3, channel.task().blockNumber());
        assertEquals(3, channel.task().blockCount());
        assertTrue(channel.task("synthetic/empty.bin", false, 0, 0, 0));
        assertEquals(0, channel.task().blockNumber());
        assertEquals(0, channel.task().blockCount());
    }

    @Test public void nonAlignedResumeCountsActualRemainingBlocksAndKeepsRetryNumber() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        long start = BLOCK_SIZE + BLOCK_SIZE / 2;
        long size = 3 * BLOCK_SIZE + BLOCK_SIZE / 4;
        assertTrue(channel.task("synthetic/resume.bin", true, start, BLOCK_SIZE, size));
        // One complete prefix block plus two actual remaining protocol blocks.
        assertEquals(2, channel.task().blockNumber());
        assertEquals(3, channel.task().blockCount());
        assertTrue(channel.task("synthetic/resume.bin", true, start + BLOCK_SIZE,
                size - start - BLOCK_SIZE, size));
        assertEquals(3, channel.task().blockNumber());
        assertEquals(3, channel.task().blockCount());
        assertTrue(channel.task("synthetic/resume.bin", true, start, BLOCK_SIZE, size));
        assertEquals(2, channel.task().blockNumber());
        assertEquals(3, channel.task().blockCount());
    }

    @Test public void legacyCallbacksConvertBlockEndPositionsWithoutAddingATailBlock() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        assertTrue(channel.taskEndingAt("synthetic/legacy.bin", true, 2 * BLOCK_SIZE, 3 * BLOCK_SIZE));
        assertEquals(BLOCK_SIZE, channel.task().offset);
        assertEquals(BLOCK_SIZE, channel.task().length);
        assertEquals(2, channel.task().blockNumber());
        assertEquals(3, channel.task().blockCount());
        assertFalse(channel.taskEndingAt("synthetic/invalid.bin", true, -1, 3 * BLOCK_SIZE));
        assertFalse(channel.taskEndingAt("synthetic/invalid.bin", true, 3 * BLOCK_SIZE + 1, 3 * BLOCK_SIZE));
        assertEquals("synthetic/legacy.bin", channel.task().path);
        assertTrue(channel.taskEndingAt("synthetic/empty.bin", false, 0, 0));
        assertEquals(0, channel.task().blockNumber());
        assertEquals(0, channel.task().blockCount());
    }
}
