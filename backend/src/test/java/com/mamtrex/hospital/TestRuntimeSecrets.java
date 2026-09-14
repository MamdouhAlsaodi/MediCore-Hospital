package com.mamtrex.hospital;

import java.security.SecureRandom;
import java.util.Base64;

/** Process-local credentials for tests that boot authenticated application contexts. */
public final class TestRuntimeSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String JWT_SECRET = randomToken(48);
    private static final String ACCOUNT_PASSWORD = randomToken(24);

    private TestRuntimeSecrets() {
    }

    public static String jwtSecret() {
        return JWT_SECRET;
    }

    public static String accountPassword() {
        return ACCOUNT_PASSWORD;
    }

    private static String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
