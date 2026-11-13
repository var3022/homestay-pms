package com.homestay.pms_backend.security.refreshtoken;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * High-Entropy Cryptographic Refresh-Token Generator and Hasher.
 *
 * Security Requirements:
 * 1. 256 bits (32 bytes) of cryptographic randomness from SecureRandom.
 * 2. URL-safe Base64 encoding without padding (exactly 43 characters).
 * 3. SHA-256 cryptographic hashing for database storage and lookup (64 lowercase hex characters).
 * 4. Input validation and length bounding to defend against Denial-of-Service / buffer attacks.
 * 5. Constant-time timing-safe hash comparison via MessageDigest.isEqual.
 * 6. Never persists or logs plaintext tokens or hashes.
 */
@Component
public class RefreshTokenGenerator {

    private static final int ENTROPY_BYTES = 32; // 256 bits of CSPRNG entropy
    private static final int MIN_TOKEN_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 128;

    private final SecureRandom secureRandom;
    private final Base64.Encoder urlEncoder;

    public RefreshTokenGenerator() {
        this.secureRandom = new SecureRandom();
        this.urlEncoder = Base64.getUrlEncoder().withoutPadding();
    }

    /**
     * Generates a new cryptographically secure opaque refresh token.
     * Contains 256 bits of entropy encoded as a URL-safe Base64 string without padding.
     */
    public String generateToken() {
        byte[] bytes = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(bytes);
        return urlEncoder.encodeToString(bytes);
    }

    /**
     * Hashes a raw refresh token using SHA-256, returning a 64-character lowercase hex string.
     *
     * @param rawToken Plaintext refresh token
     * @return 64-character hexadecimal SHA-256 hash
     * @throws IllegalArgumentException if token is null, blank, or invalid format
     */
    public String hashToken(String rawToken) {
        if (!isValidTokenFormat(rawToken)) {
            throw new IllegalArgumentException("Invalid refresh token format or length");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest algorithm not available", e);
        }
    }

    /**
     * Validates that the provided raw token conforms to expected length and URL-safe character boundaries.
     */
    public boolean isValidTokenFormat(String rawToken) {
        if (rawToken == null) {
            return false;
        }
        String trimmed = rawToken.trim();
        if (trimmed.length() < MIN_TOKEN_LENGTH || trimmed.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean isAlphaNum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            boolean isUrlSafeSpecial = (c == '-' || c == '_');
            if (!isAlphaNum && !isUrlSafeSpecial) {
                return false;
            }
        }
        return true;
    }

    /**
     * Timing-safe comparison of two hash strings.
     */
    public boolean timingSafeEquals(String hashA, String hashB) {
        if (hashA == null || hashB == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hashA.getBytes(StandardCharsets.UTF_8),
                hashB.getBytes(StandardCharsets.UTF_8)
        );
    }
}
