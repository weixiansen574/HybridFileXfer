package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.security.Pairing;

/** Immutable, process-local view of a user-approved server for the trusted ADB bridge. */
final class AdbPairingState {
    private static final AdbPairingState IDLE = new AdbPairingState("HFXPAIR1:idle", false);
    private static final AdbPairingState BUSY = new AdbPairingState("HFXPAIR1:busy", false);
    private final String payload;
    private final boolean ready;

    private AdbPairingState(String payload, boolean ready) {
        this.payload = payload;
        this.ready = ready;
    }

    static AdbPairingState idle() {
        return IDLE;
    }

    static AdbPairingState ready(int port, String secret) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid server port");
        String normalized = Pairing.validateSecret(secret);
        return new AdbPairingState("HFXPAIR1:ready:" + port + ":" + normalized, true);
    }

    AdbPairingState connected() {
        return ready || this == BUSY ? BUSY : IDLE;
    }

    AdbPairingState stopped() {
        return IDLE;
    }

    static void requireCaller(int uid) {
        // Compare the complete Linux UID, not its per-user app ID. Ordinary apps,
        // including this app itself, cannot request the secret through the provider.
        if (uid != 2000 && uid != 0) {
            throw new SecurityException("Pairing lookup requires an authorized ADB shell");
        }
    }

    String payloadForCaller(int uid) {
        requireCaller(uid);
        return payload;
    }
}
