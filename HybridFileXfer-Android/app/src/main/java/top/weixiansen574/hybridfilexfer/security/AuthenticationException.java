package top.weixiansen574.hybridfilexfer.security;

import java.io.IOException;

/** 配对码认证失败异常；遇到此异常时客户端应停止自动重连并提示用户检查配对码。 */
public class AuthenticationException extends IOException {
    public AuthenticationException(String message) { super(message); }
}
