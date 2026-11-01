package com.marketchat.commerce.checkout.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 128-bit random confirmation tokens. Only their SHA-256 is stored; comparison is constant-time (spec §6.1-6.2).
 * The token goes to the app only, never to the LLM, so the agent can never confirm an order by itself.
 */
public final class ConfirmationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ConfirmationTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String token, byte[] expectedHash) {
        return token != null && !token.isEmpty() && MessageDigest.isEqual(hash(token), expectedHash);
    }
}
