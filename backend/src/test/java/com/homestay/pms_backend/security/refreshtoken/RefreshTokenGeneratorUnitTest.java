package com.homestay.pms_backend.security.refreshtoken;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 3.3C.5.1: RefreshTokenGenerator Unit Tests")
class RefreshTokenGeneratorUnitTest {

    private RefreshTokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new RefreshTokenGenerator();
    }

    @Test
    @DisplayName("Generates URL-safe tokens with at least 256 bits of entropy (43 chars, no padding)")
    void shouldGenerateUrlSafeHighEntropyTokens() {
        String token = generator.generateToken();

        assertThat(token).isNotBlank();
        // 32 bytes Base64URL without padding is exactly 43 characters
        assertThat(token).hasSize(43);
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(generator.isValidTokenFormat(token)).isTrue();
    }

    @Test
    @DisplayName("Generated tokens are unique across successive invocations")
    void shouldGenerateUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = generator.generateToken();
            boolean added = tokens.add(token);
            assertThat(added).isTrue();
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    @DisplayName("Hashes tokens using SHA-256 producing 64-character lowercase hex string")
    void shouldHashTokenWithSha256() {
        String token = generator.generateToken();
        String hash = generator.hashToken(token);

        assertThat(hash).isNotBlank();
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Hash operation is deterministic for identical input")
    void shouldProduceDeterministicHash() {
        String token = generator.generateToken();
        String hash1 = generator.hashToken(token);
        String hash2 = generator.hashToken(token);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Rejects malformed, blank, or out-of-bounds token formats")
    void shouldRejectMalformedTokens() {
        assertThat(generator.isValidTokenFormat(null)).isFalse();
        assertThat(generator.isValidTokenFormat("")).isFalse();
        assertThat(generator.isValidTokenFormat("   ")).isFalse();
        // Too short (< 32 chars)
        assertThat(generator.isValidTokenFormat("shortToken")).isFalse();
        // Disallowed characters (spaces, plus, slash, equals)
        assertThat(generator.isValidTokenFormat("12345678901234567890123456789012+bad")).isFalse();
        assertThat(generator.isValidTokenFormat("12345678901234567890123456789012/bad")).isFalse();
        assertThat(generator.isValidTokenFormat("12345678901234567890123456789012=bad")).isFalse();

        assertThatThrownBy(() -> generator.hashToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.hashToken("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Timing-safe comparison correctly compares hashes")
    void shouldCompareHashesTimingSafely() {
        String hashA = "a".repeat(64);
        String hashB = "a".repeat(64);
        String hashC = "b".repeat(64);

        assertThat(generator.timingSafeEquals(hashA, hashB)).isTrue();
        assertThat(generator.timingSafeEquals(hashA, hashC)).isFalse();
        assertThat(generator.timingSafeEquals(hashA, null)).isFalse();
        assertThat(generator.timingSafeEquals(null, hashB)).isFalse();
    }

    @Test
    @DisplayName("IssuedRefreshToken and RotatedRefreshToken redact raw tokens in toString()")
    void shouldRedactRawTokensInToString() {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(604800);
        String secretToken = "superSecretRawRefreshTokenThatMustNotBeLogged_12345";

        IssuedRefreshToken issued = new IssuedRefreshToken(secretToken, tokenId, familyId, 1, expiry, staffId);
        assertThat(issued.toString()).doesNotContain(secretToken);
        assertThat(issued.toString()).contains("***REDACTED***");
        assertThat(issued.rawToken()).isEqualTo(secretToken);

        RotatedRefreshToken rotated = new RotatedRefreshToken(secretToken, tokenId, familyId, 2, expiry, staffId);
        assertThat(rotated.toString()).doesNotContain(secretToken);
        assertThat(rotated.toString()).contains("***REDACTED***");
        assertThat(rotated.rawToken()).isEqualTo(secretToken);
    }
}
