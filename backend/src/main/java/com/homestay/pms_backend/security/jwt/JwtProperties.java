package com.homestay.pms_backend.security.jwt;

import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration properties for JWT access-token signing and validation.
 * Supports server-side key rotation via key ID allowlists and enforces a 256-bit
 * minimum key length for HMAC-SHA256 (HS256).
 */
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private static final int MINIMUM_KEY_BYTES = 32; // 256 bits

    private boolean enabled = false;
    private String issuer = "homestay-pms";
    private String audience = "homestay-pms-client";
    private long accessTokenTtlSeconds = 900; // 15 minutes
    private long clockSkewSeconds = 60; // 60s tolerance for clock drift
    private String activeKeyId = "k1";
    private Map<String, String> keys = new HashMap<>();

    private final Map<String, SecretKey> secretKeys = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        validate();
    }

    /**
     * Validates configuration hygiene and materializes SecretKey instances.
     * Fails closed when enabled and configuration is invalid or missing.
     */
    public void validate() {
        if (!enabled) {
            return;
        }

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.issuer must not be null or blank when JWT is enabled.");
        }

        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.audience must not be null or blank when JWT is enabled.");
        }

        if (accessTokenTtlSeconds <= 0) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.access-token-ttl-seconds must be greater than zero.");
        }

        if (clockSkewSeconds < 0) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.clock-skew-seconds must be non-negative.");
        }

        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.active-key-id must not be null or blank.");
        }

        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.keys map must contain at least one signing key.");
        }

        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.keys does not contain active key ID '" + activeKeyId + "'.");
        }

        secretKeys.clear();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String kid = entry.getKey();
            String base64Secret = entry.getValue();

            if (kid == null || kid.isBlank()) {
                throw new IllegalStateException("FATAL CONFIGURATION ERROR: jwt.keys contains a blank or null key ID.");
            }

            if (base64Secret == null || base64Secret.isBlank()) {
                throw new IllegalStateException("FATAL CONFIGURATION ERROR: Missing secret for key ID '" + kid + "'.");
            }

            byte[] decodedBytes;
            try {
                decodedBytes = Base64.getDecoder().decode(base64Secret.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("FATAL CONFIGURATION ERROR: Key ID '" + kid + "' has an invalid Base64 secret.", e);
            }

            if (decodedBytes.length < MINIMUM_KEY_BYTES) {
                throw new IllegalStateException(
                        "FATAL SECURITY VIOLATION: Key ID '" + kid + "' secret length (" + (decodedBytes.length * 8) +
                        " bits) is below the minimum required 256 bits (32 bytes) for HS256."
                );
            }

            SecretKey secretKey = Keys.hmacShaKeyFor(decodedBytes);
            secretKeys.put(kid.trim(), secretKey);
        }
    }

    public SecretKey getSecretKey(String keyId) {
        if (keyId == null) {
            return null;
        }
        return secretKeys.get(keyId.trim());
    }

    public SecretKey getActiveSecretKey() {
        SecretKey key = secretKeys.get(activeKeyId);
        if (key == null) {
            throw new IllegalStateException("Active key ID '" + activeKeyId + "' is not loaded or missing from key registry.");
        }
        return key;
    }

    public Map<String, SecretKey> getSecretKeys() {
        return Collections.unmodifiableMap(secretKeys);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    @Override
    public String toString() {
        return "JwtProperties{" +
                "enabled=" + enabled +
                ", issuer='" + issuer + '\'' +
                ", audience='" + audience + '\'' +
                ", accessTokenTtlSeconds=" + accessTokenTtlSeconds +
                ", clockSkewSeconds=" + clockSkewSeconds +
                ", activeKeyId='" + activeKeyId + '\'' +
                ", configuredKeyIds=" + (keys != null ? keys.keySet() : "[]") +
                '}';
    }
}
