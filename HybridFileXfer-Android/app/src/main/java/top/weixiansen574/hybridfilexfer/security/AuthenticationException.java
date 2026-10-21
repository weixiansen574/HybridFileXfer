package top.weixiansen574.hybridfilexfer.security;

import java.io.IOException;

/** A permanent pairing failure; retry only after the user corrects the code. */
public class AuthenticationException extends IOException {
    public AuthenticationException(String message) { super(message); }
}
