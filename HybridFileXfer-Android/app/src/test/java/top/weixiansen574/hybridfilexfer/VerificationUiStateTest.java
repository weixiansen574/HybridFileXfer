package top.weixiansen574.hybridfilexfer;

import org.junit.Test;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;

import static org.junit.Assert.*;

public class VerificationUiStateTest {
    private static VerificationProgress progress(int round, long total, long checked, String phase) {
        return new VerificationProgress(round, total, checked, checked, 0, phase, "synthetic/file.bin");
    }

    @Test public void verificationStopIsUnavailableUntilTheTransferEntersVerification() {
        VerificationUiState state = new VerificationUiState();
        assertFalse(state.active());
        assertFalse(state.requestStop());
        state.begin();
        assertFalse(state.active());
        assertTrue(state.transferringData());
        assertFalse(state.requestStop());
        assertTrue(state.update(progress(1, 8, 2, "verifying")));
        assertTrue(state.active());
        assertFalse(state.transferringData());
        assertTrue(state.canStop());
        assertEquals(6, state.uncheckedFiles());
    }

    @Test public void stopRemainsAvailableDuringPeerWaitAndMismatchRetries() {
        VerificationUiState state = new VerificationUiState();
        state.begin();
        for (String phase : new String[] {"verifying", "waiting-peer", "retrying"}) {
            assertTrue(state.update(progress(1, 8, 2, phase)));
            assertTrue(state.canStop());
            assertEquals("retrying".equals(phase), state.transferringData());
        }
        assertTrue(state.requestStop());
        assertTrue(state.stopping());
        assertFalse(state.requestStop());
        assertFalse(state.canStop());
        assertTrue(state.update(progress(2, 3, 1, "verifying")));
        assertTrue(state.stopping());
        assertFalse(state.canStop());
    }

    @Test public void remoteStopAlsoDisablesTheLocalActionPermanently() {
        VerificationUiState state = new VerificationUiState();
        state.begin();
        state.update(progress(1, 8, 2, "stopping"));
        assertTrue(state.stopping());
        assertFalse(state.canStop());
        state.update(progress(1, 8, 3, "waiting-peer"));
        assertFalse(state.canStop());
        assertEquals(5, state.uncheckedFiles());
    }

    @Test public void oldRoundsCannotReplaceTheCurrentRound() {
        VerificationUiState state = new VerificationUiState();
        state.begin();
        VerificationProgress current = progress(2, 3, 1, "verifying");
        state.update(current);
        assertFalse(state.update(progress(1, 8, 8, "waiting-peer")));
        assertSame(current, state.progress());
        assertEquals(2, state.uncheckedFiles());
    }

    @Test public void finishedStateRejectsLateEventsAndANewTransferStartsCleanly() {
        VerificationUiState state = new VerificationUiState();
        state.begin();
        state.update(progress(1, 8, 2, "verifying"));
        state.requestStop();
        state.finish();
        assertFalse(state.active());
        assertFalse(state.canStop());
        assertFalse(state.update(progress(2, 3, 1, "retrying")));
        state.begin();
        assertNull(state.progress());
        assertFalse(state.stopping());
        assertFalse(state.active());
        assertTrue(state.update(progress(1, 4, 0, "verifying")));
        assertTrue(state.canStop());
    }

    @Test public void emptyAndLargeCountsRemainExact() {
        VerificationUiState state = new VerificationUiState();
        state.begin();
        state.update(progress(1, 0, 0, "verifying"));
        assertEquals(0, state.uncheckedFiles());
        state.update(progress(1, Long.MAX_VALUE, Long.MAX_VALUE - 1, "verifying"));
        assertEquals(1, state.uncheckedFiles());
        assertFalse(state.update(null));
    }
}
