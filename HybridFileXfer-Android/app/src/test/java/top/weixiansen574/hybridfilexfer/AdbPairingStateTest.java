package top.weixiansen574.hybridfilexfer;

import org.junit.Test;

import static org.junit.Assert.*;

public class AdbPairingStateTest {
    private static final String CODE = "SYNTHETIC_PAIRING_CODE";

    @Test public void shellAndRootCanReadTheApprovedServerCodeAndActualPort() {
        AdbPairingState state = AdbPairingState.ready(60123, CODE);
        assertEquals("HFXPAIR1:ready:60123:" + CODE, state.payloadForCaller(2000));
        assertEquals(state.payloadForCaller(2000), state.payloadForCaller(0));
        assertEquals("HFXPAIR1:ready:1024:" + CODE,
                AdbPairingState.ready(1024, CODE).payloadForCaller(2000));
        assertEquals("HFXPAIR1:ready:65535:" + CODE,
                AdbPairingState.ready(65535, CODE).payloadForCaller(2000));
    }

    @Test public void ordinaryAppsAndPrivilegedNonShellUidsAreDeniedInEveryState() {
        AdbPairingState[] states = {
                AdbPairingState.idle(), AdbPairingState.ready(5740, CODE),
                AdbPairingState.ready(5740, CODE).connected()
        };
        int[] deniedUids = {-1, 1, 1000, 1001, 2001, 10000, 10123, 100000, 102000, 110123};
        for (AdbPairingState state : states) {
            for (int uid : deniedUids) {
                try {
                    state.payloadForCaller(uid);
                    fail("Accepted unauthorized UID " + uid);
                } catch (SecurityException expected) {
                    assertFalse(expected.getMessage().contains(CODE));
                }
            }
        }
    }

    @Test public void unstartedAndStoppedSessionsExposeNoCode() {
        assertEquals("HFXPAIR1:idle", AdbPairingState.idle().payloadForCaller(2000));
        AdbPairingState stopped = AdbPairingState.ready(5740, CODE).stopped();
        assertEquals("HFXPAIR1:idle", stopped.payloadForCaller(2000));
        assertEquals("HFXPAIR1:idle", stopped.connected().payloadForCaller(2000));
    }

    @Test public void connectionRevokesCodeAndStoppingClearsBusyState() {
        AdbPairingState connected = AdbPairingState.ready(5740, CODE).connected();
        assertEquals("HFXPAIR1:busy", connected.payloadForCaller(2000));
        assertEquals("HFXPAIR1:busy", connected.connected().payloadForCaller(2000));
        assertEquals("HFXPAIR1:idle", connected.stopped().payloadForCaller(2000));
    }

    @Test public void aNewApprovedStartUsesOnlyTheNewPortAndCode() {
        AdbPairingState previous = AdbPairingState.ready(5740, CODE);
        AdbPairingState current = previous.stopped();
        assertEquals("HFXPAIR1:idle", current.payloadForCaller(2000));
        current = AdbPairingState.ready(5741, "NEXT_SYNTHETIC_CODE");
        assertEquals("HFXPAIR1:ready:5741:NEXT_SYNTHETIC_CODE", current.payloadForCaller(2000));
        assertFalse(current.payloadForCaller(2000).contains(CODE));
        // Published snapshots stay immutable when the main thread replaces one.
        assertEquals("HFXPAIR1:ready:5740:" + CODE, previous.payloadForCaller(2000));
    }

    @Test public void codesUseTheSameNormalizationAsTransportAuthentication() {
        AdbPairingState state = AdbPairingState.ready(5740, " synthetic_pairing_code ");
        assertEquals("HFXPAIR1:ready:5740:" + CODE, state.payloadForCaller(2000));
    }

    @Test public void invalidPortsAndWireDelimitersCannotCreateAReadyPayload() {
        for (int port : new int[] {-1, 0, 1, 1023, 65536}) {
            try {
                AdbPairingState.ready(port, CODE);
                fail("Accepted invalid port " + port);
            } catch (IllegalArgumentException expected) { }
        }
        for (String code : new String[] {null, "", "SHORT", CODE + ":idle", CODE + "\nOTHER"}) {
            try {
                AdbPairingState.ready(5740, code);
                fail("Accepted invalid code");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
