package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.security.Pairing;

/** 保存当前已启动服务端会话的只读快照，供 ADB 自动配对查询。 */
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
        // 校验完整的 Linux UID，仅允许 ADB shell (2000) 或 root (0) 调用。
        if (uid != 2000 && uid != 0) {
            throw new SecurityException("Pairing lookup requires an authorized ADB shell");
        }
    }

    String payloadForCaller(int uid) {
        requireCaller(uid);
        return payload;
    }
}
