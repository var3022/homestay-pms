package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.security.AuthenticatedStaff;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 3.3C.3: JWT Token Service Unit Tests")
class JwtTokenServiceUnitTest {

    // Valid 256-bit (32-byte) Base64-encoded test keys
    private static final String KEY_K1 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="; // "01234567890123456789012345678901"
    private static final String KEY_K2 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDI="; // "01234567890123456789012345678902"
    private static final String UNTRUSTED_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5OTk="; // "01234567890123456789012345678999"

    private static final Instant FIXED_NOW = Instant.parse("2026-11-13T12:00:00Z");

    private MutableClock testClock;
    private JwtProperties jwtProperties;
    private JwtTokenService jwtTokenService;
    private AuthenticatedStaff sampleStaff;

    @BeforeEach
    void setUp() {
        testClock = new MutableClock(FIXED_NOW);

        jwtProperties = new JwtProperties();
        jwtProperties.setEnabled(true);
        jwtProperties.setIssuer("homestay-pms-test");
        jwtProperties.setAudience("homestay-pms-test-client");
        jwtProperties.setAccessTokenTtlSeconds(900); // 15 minutes
        jwtProperties.setClockSkewSeconds(60);
        jwtProperties.setActiveKeyId("k1");

        Map<String, String> keys = new HashMap<>();
        keys.put("k1", KEY_K1);
        keys.put("k2", KEY_K2);
        jwtProperties.setKeys(keys);
        jwtProperties.validate();

        jwtTokenService = new JwtTokenService(jwtProperties, testClock);

        sampleStaff = new AuthenticatedStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "staff.member@example.com",
                "Alice",
                "Smith",
                "+919876543210"
        );
    }

    // =========================================================================
    // 1. Token Generation & Standard Claims
    // =========================================================================

    @Test
    @DisplayName("Generation: Generates valid access token with all required standard and custom claims")
    void shouldGenerateValidAccessTokenWithStandardClaims() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);

        assertThat(dto).isNotNull();
        assertThat(dto.token()).isNotBlank();
        assertThat(dto.tokenType()).isEqualTo("access");
        assertThat(dto.jti()).isNotBlank();
        assertThat(dto.expiresAt()).isEqualTo(FIXED_NOW.plus(15, ChronoUnit.MINUTES));

        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());
        assertThat(claims.staffUserId()).isEqualTo(sampleStaff.id());
        assertThat(claims.issuer()).isEqualTo("homestay-pms-test");
        assertThat(claims.audience()).isEqualTo("homestay-pms-test-client");
        assertThat(claims.issuedAt()).isEqualTo(FIXED_NOW);
        assertThat(claims.iatMillis()).isEqualTo(FIXED_NOW.toEpochMilli());
        assertThat(claims.notBefore()).isEqualTo(FIXED_NOW);
        assertThat(claims.expiresAt()).isEqualTo(FIXED_NOW.plus(15, ChronoUnit.MINUTES));
        assertThat(claims.jti()).isEqualTo(dto.jti());
        assertThat(claims.tokenType()).isEqualTo("access");
        assertThat(claims.keyId()).isEqualTo("k1");
    }

    @Test
    @DisplayName("Expiration: Enforces exact 15-minute (900 seconds) token lifetime")
    void shouldEnforceExact15MinuteLifetime() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);
        Duration ttl = Duration.between(FIXED_NOW, dto.expiresAt());
        assertThat(ttl.toSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("JTI Uniqueness: Generates distinct cryptographic random JTIs across successive calls")
    void shouldGenerateUniqueJtiForSuccessiveTokens() {
        AccessTokenDto token1 = jwtTokenService.generateAccessToken(sampleStaff);
        AccessTokenDto token2 = jwtTokenService.generateAccessToken(sampleStaff);

        assertThat(token1.jti()).isNotEqualTo(token2.jti());
        assertThat(token1.token()).isNotEqualTo(token2.token());
    }

    // =========================================================================
    // 2. Cryptographic & Tamper Protection
    // =========================================================================

    @Test
    @DisplayName("Tamper Protection: Rejects token with modified signature")
    void shouldRejectTokenWithModifiedSignature() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);
        String[] parts = dto.token().split("\\.");
        assertThat(parts).hasSize(3);

        // Tamper with last character of signature
        char lastChar = parts[2].charAt(parts[2].length() - 1);
        char tamperedChar = (lastChar == 'A') ? 'B' : 'A';
        String tamperedToken = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1) + tamperedChar;

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tamperedToken))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Tamper Protection: Rejects token with modified payload content")
    void shouldRejectTokenWithModifiedPayload() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);
        String[] parts = dto.token().split("\\.");

        // Decode payload, modify sub, re-encode without signature update
        String decodedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String modifiedPayload = decodedPayload.replace(sampleStaff.id().toString(), UUID.randomUUID().toString());
        String encodedModifiedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(modifiedPayload.getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + encodedModifiedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tamperedToken))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Algorithm Confusion: Rejects token signed with 'none' algorithm")
    void shouldRejectUnsecuredAlgorithmNone() {
        String unsignedToken = Jwts.builder()
                .header().type("JWT").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(unsignedToken))
                .isInstanceOf(JwtValidationException.class);
    }

    // =========================================================================
    // 3. Temporal Validity & Clock Skew
    // =========================================================================

    @Test
    @DisplayName("Expiration: Rejects expired token after 15 minutes plus clock skew")
    void shouldRejectExpiredToken() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);

        // Advance clock to 15 minutes + 61 seconds (past 60s clock skew window)
        testClock.setInstant(FIXED_NOW.plusSeconds(900 + 61));

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(dto.token()))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Clock Skew: Tolerates small clock drift within allowed skew seconds")
    void shouldTolerateSmallClockDriftWithinSkewWindow() {
        AccessTokenDto dto = jwtTokenService.generateAccessToken(sampleStaff);

        // Advance clock by 900 seconds + 30 seconds (within 60s skew allowance)
        testClock.setInstant(FIXED_NOW.plusSeconds(900 + 30));

        StaffJwtClaims claims = jwtTokenService.validateAccessToken(dto.token());
        assertThat(claims).isNotNull();
        assertThat(claims.staffUserId()).isEqualTo(sampleStaff.id());
    }

    @Test
    @DisplayName("Not Before: Rejects token with future not-before time")
    void shouldRejectFutureNotBeforeToken() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        Instant futureNbf = FIXED_NOW.plus(5, ChronoUnit.MINUTES);

        String futureToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(futureNbf))
                .expiration(Date.from(futureNbf.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(futureToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("not-before");
    }

    // =========================================================================
    // 4. Claims Structure Enforcement
    // =========================================================================

    @Test
    @DisplayName("Issuer Verification: Rejects token with mismatched issuer")
    void shouldRejectMismatchedIssuer() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String invalidIssuerToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer("malicious-issuer")
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", FIXED_NOW.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(invalidIssuerToken))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Audience Verification: Rejects token with mismatched audience")
    void shouldRejectMismatchedAudience() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String invalidAudienceToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add("untrusted-audience").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", FIXED_NOW.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(invalidAudienceToken))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Token Confusion: Rejects token with incorrect or missing token_type claim")
    void shouldRejectIncorrectTokenType() {
        SecretKey key = jwtProperties.getActiveSecretKey();

        // 1. token_type = "refresh"
        String refreshToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "refresh")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(refreshToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("token_type");

        // 2. Missing token_type entirely
        String noTypeToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(noTypeToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("token_type");
    }

    @Test
    @DisplayName("Subject Validation: Rejects token with non-UUID subject")
    void shouldRejectNonUuidSubject() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String nonUuidSubToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject("admin_user_not_uuid")
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", FIXED_NOW.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(nonUuidSubToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    @DisplayName("Malformed Tokens: Rejects null, blank, truncated, or non-JWT tokens")
    void shouldRejectMalformedTokens() {
        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(null))
                .isInstanceOf(JwtValidationException.class);

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(""))
                .isInstanceOf(JwtValidationException.class);

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken("   "))
                .isInstanceOf(JwtValidationException.class);

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken("not-a-valid-token"))
                .isInstanceOf(JwtValidationException.class);

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken("part1.part2"))
                .isInstanceOf(JwtValidationException.class);
    }

    // =========================================================================
    // 5. Strict iat_millis Claim Enforcement (No Fallback)
    // =========================================================================

    @Test
    @DisplayName("iat_millis: Rejects token with missing iat_millis claim")
    void shouldRejectTokenWithMissingIatMillis() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String tokenWithoutIatMillis = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                // Explicitly omitting iat_millis
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tokenWithoutIatMillis))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Missing required iat_millis claim");
    }

    @Test
    @DisplayName("iat_millis: Rejects token with malformed or non-numeric iat_millis claim")
    void shouldRejectTokenWithMalformedOrNonNumericIatMillis() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String tokenWithNonNumericIatMillis = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", "not-a-number")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tokenWithNonNumericIatMillis))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Malformed or non-numeric iat_millis claim");
    }

    @Test
    @DisplayName("iat_millis: Rejects token with non-positive (zero or negative) iat_millis claim")
    void shouldRejectTokenWithNonPositiveIatMillis() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        String tokenWithZeroIatMillis = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", 0L)
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(tokenWithZeroIatMillis))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("iat_millis: Rejects token when standard iat and iat_millis are inconsistent/contradictory")
    void shouldRejectTokenWithInconsistentIatAndIatMillis() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        // iat corresponds to FIXED_NOW, but iat_millis corresponds to 5 seconds later
        Instant contradictoryMillis = FIXED_NOW.plusSeconds(5);

        String inconsistentToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", contradictoryMillis.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(inconsistentToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Inconsistent iat and iat_millis claims");
    }

    @Test
    @DisplayName("iat_millis: Rejects token issued in the future beyond configured clock skew")
    void shouldRejectTokenIssuedInFutureBeyondClockSkew() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        // 60s clock skew configured; token issued 70s in the future
        Instant futureIssuedAt = FIXED_NOW.plusSeconds(jwtProperties.getClockSkewSeconds() + 10);

        String futureToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(futureIssuedAt))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(futureIssuedAt.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", futureIssuedAt.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(futureToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Token issued in the future beyond allowed clock skew");
    }

    @Test
    @DisplayName("iat_millis: Accepts token issued in the future within configured clock skew allowance")
    void shouldAcceptTokenIssuedInFutureWithinClockSkew() {
        SecretKey key = jwtProperties.getActiveSecretKey();
        // 60s clock skew configured; token issued 30s in the future (within skew tolerance)
        Instant futureWithinSkew = FIXED_NOW.plusSeconds(30);

        String validFutureToken = Jwts.builder()
                .header().type("JWT").keyId("k1").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(futureWithinSkew))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(futureWithinSkew.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .claim("iat_millis", futureWithinSkew.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        StaffJwtClaims claims = jwtTokenService.validateAccessToken(validFutureToken);
        assertThat(claims).isNotNull();
        assertThat(claims.issuedAt()).isEqualTo(futureWithinSkew);
        assertThat(claims.iatMillis()).isEqualTo(futureWithinSkew.toEpochMilli());
    }

    // =========================================================================
    // 6. Server-Side Key Rotation
    // =========================================================================

    @Test
    @DisplayName("Key Rotation: Validates tokens signed with active key and historical trusted keys")
    void shouldSupportKeyRotationAcrossTrustedKeys() {
        // Step 1: Issue token with active key k1
        AccessTokenDto tokenK1 = jwtTokenService.generateAccessToken(sampleStaff);
        StaffJwtClaims claimsK1 = jwtTokenService.validateAccessToken(tokenK1.token());
        assertThat(claimsK1.keyId()).isEqualTo("k1");

        // Step 2: Rotate active key to k2 (both k1 and k2 remain in keys map)
        jwtProperties.setActiveKeyId("k2");

        // Step 3: Issue new token with active key k2
        AccessTokenDto tokenK2 = jwtTokenService.generateAccessToken(sampleStaff);
        StaffJwtClaims claimsK2 = jwtTokenService.validateAccessToken(tokenK2.token());
        assertThat(claimsK2.keyId()).isEqualTo("k2");

        // Step 4: Verify historical token signed with k1 is STILL valid against key allowlist
        StaffJwtClaims verifiedOldToken = jwtTokenService.validateAccessToken(tokenK1.token());
        assertThat(verifiedOldToken.keyId()).isEqualTo("k1");
    }

    @Test
    @DisplayName("Key Rotation: Rejects token signed with unknown/untrusted key ID")
    void shouldRejectTokenSignedWithUnknownKeyId() {
        byte[] untrustedBytes = Base64.getDecoder().decode(UNTRUSTED_KEY);
        SecretKey untrustedKey = Keys.hmacShaKeyFor(untrustedBytes);

        String untrustedToken = Jwts.builder()
                .header().type("JWT").keyId("k99").and()
                .subject(sampleStaff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(FIXED_NOW))
                .notBefore(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plus(15, ChronoUnit.MINUTES)))
                .claim("token_type", "access")
                .signWith(untrustedKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.validateAccessToken(untrustedToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Unknown or untrusted key ID");
    }

    // =========================================================================
    // 6. Security & Credential Hygiene
    // =========================================================================

    @Test
    @DisplayName("Credential Hygiene: JwtProperties.toString() masks signing keys and secrets")
    void shouldMaskSecretsInToString() {
        String propertiesString = jwtProperties.toString();
        assertThat(propertiesString).doesNotContain(KEY_K1);
        assertThat(propertiesString).doesNotContain(KEY_K2);
        assertThat(propertiesString).contains("k1");
        assertThat(propertiesString).contains("k2");
    }

    // =========================================================================
    // Helper MutableClock for Deterministic Temporal Testing
    // =========================================================================

    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zoneId = ZoneId.of("UTC");

        MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
