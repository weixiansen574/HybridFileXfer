package top.weixiansen574.hybridfilexfer.security;

import java.io.IOException;

public class ProtocolMismatchException extends IOException {
    public ProtocolMismatchException(int local, int remote) {
        super("Protocol versions differ (computer " + local + ", phone " + remote
                + "). Install the matching Android and desktop builds.");
    }
}
