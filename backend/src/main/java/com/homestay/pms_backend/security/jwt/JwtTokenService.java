package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.security.AuthenticatedStaff;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.PrematureJwtException;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Enterprise JWT Access-Token Generation and Validation Service.
 *
 * Implements strict stateless token lifecycle:
 * 1. 15-minute lifetime by default, signed via HMAC-SHA256 (HS256).
 * 2. Mandatory standard claims: sub (staff UUID), iss, aud, iat, nbf, exp, jti.
 * 3. Token-confusion defense via mandatory custom claim 'token_type: access'.
 * 4. Header validation: typ=JWT, strictly enforced alg=HS256, kid bound to verified server key.
 * 5. Server-side key rotation support via trusted key ID allowlist.
 * 6. Injectable Clock for deterministic, skew-safe testing.
 */
@Service
@ConditionalOnProperty(prefix = "jwt", name = "enabled", havingValue = "true")
public class JwtTokenService implements AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String JWT_HEADER_TYPE = "JWT";

    private final JwtProperties jwtProperties;
    private final Clock clock;

    @Autowired
    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    public JwtTokenService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    @Override
    public AccessTokenDto generateAccessToken(AuthenticatedStaff staff) {
        if (staff == null || staff.id() == null) {
            throw new IllegalArgumentException("Staff identity must not be null and must have a valid ID");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plusSeconds(jwtProperties.getAccessTokenTtlSeconds());
        String jti = UUID.randomUUID().toString();
        String activeKeyId = jwtProperties.getActiveKeyId();
        SecretKey signingKey = jwtProperties.getActiveSecretKey();

        String token = Jwts.builder()
                .header()
                .type(JWT_HEADER_TYPE)
                .keyId(activeKeyId)
                .and()
                .subject(staff.id().toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(jti)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("token_type", ACCESS_TOKEN_TYPE)
                .claim("iat_millis", now.toEpochMilli())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new AccessTokenDto(token, expiresAt, jti, ACCESS_TOKEN_TYPE);
    }

    @Override
    public StaffJwtClaims validateAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException("Access token must not be null or blank");
        }

        io.jsonwebtoken.Clock jjwtClock = () -> Date.from(clock.instant());

        Locator<Key> locator = header -> {
            String alg = header.getAlgorithm();
            if (alg == null || !Jwts.SIG.HS256.getId().equals(alg)) {
                log.warn("Rejected access token with unsupported algorithm: [{}]", alg);
                throw new JwtValidationException("Invalid token algorithm: " + alg);
            }

            String kid = (header instanceof ProtectedHeader protectedHeader)
                    ? protectedHeader.getKeyId()
                    : (String) header.get(JwsHeader.KEY_ID);

            if (kid == null || kid.isBlank()) {
                log.warn("Rejected access token with missing key ID (kid)");
                throw new JwtValidationException("Missing key ID (kid) in token header");
            }

            SecretKey key = jwtProperties.getSecretKey(kid);
            if (key == null) {
                log.warn("Rejected access token signed with unknown/untrusted key ID [{}]", kid);
                throw new JwtValidationException("Unknown or untrusted key ID: " + kid);
            }

            return key;
        };

        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .clock(jjwtClock)
                    .clockSkewSeconds(jwtProperties.getClockSkewSeconds())
                    .requireIssuer(jwtProperties.getIssuer())
                    .requireAudience(jwtProperties.getAudience())
                    .keyLocator(locator)
                    .build()
                    .parseSignedClaims(token.trim());
        } catch (JwtValidationException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            log.debug("Access token expired: {}", e.getMessage());
            throw new JwtValidationException("Access token is expired", e);
        } catch (PrematureJwtException e) {
            log.debug("Access token not yet valid (future nbf): {}", e.getMessage());
            throw new JwtValidationException("Access token cannot be used before not-before time", e);
        } catch (MalformedJwtException | UnsupportedJwtException | SecurityException e) {
            log.warn("Access token failed cryptographic/structural validation: {}", e.getMessage());
            throw new JwtValidationException("Access token is malformed, untrusted, or has invalid signature", e);
        } catch (Exception e) {
            log.warn("Unexpected failure validating access token: {}", e.getMessage());
            throw new JwtValidationException("Failed to validate access token", e);
        }

        JwsHeader header = jws.getHeader();
        Claims payload = jws.getPayload();

        // 1. Header Type Validation
        String typ = header.getType();
        if (typ != null && !JWT_HEADER_TYPE.equalsIgnoreCase(typ)) {
            log.warn("Rejected token with unexpected header type: [{}]", typ);
            throw new JwtValidationException("Invalid token header type: " + typ);
        }

        // 2. Token Type Claim Validation
        Object tokenType = payload.get("token_type");
        if (tokenType == null || !ACCESS_TOKEN_TYPE.equals(tokenType.toString())) {
            log.warn("Rejected token with invalid or missing token_type claim: [{}]", tokenType);
            throw new JwtValidationException("Invalid or missing token_type claim: " + tokenType);
        }

        // 3. Subject Claim Validation (Must parse as valid UUID)
        String sub = payload.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new JwtValidationException("Missing subject (sub) claim");
        }
        UUID staffUserId;
        try {
            staffUserId = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected token with non-UUID subject: [{}]", sub);
            throw new JwtValidationException("Subject (sub) is not a valid UUID: " + sub, e);
        }

        // 4. JWT ID Validation
        String jti = payload.getId();
        if (jti == null || jti.isBlank()) {
            throw new JwtValidationException("Missing JWT ID (jti) claim");
        }

        // 5. Timestamps Validation
        Date iatDate = payload.getIssuedAt();
        Date expDate = payload.getExpiration();
        Date nbfDate = payload.getNotBefore();
        if (iatDate == null || expDate == null || nbfDate == null) {
            throw new JwtValidationException("Missing required timestamp claims (iat, exp, nbf)");
        }
        if (!expDate.after(iatDate)) {
            throw new JwtValidationException("Token expiration must be strictly after issued-at time");
        }

        // 6. Strict iat_millis Claim Validation (No Fallback)
        Object iatMillisClaim = payload.get("iat_millis");
        if (iatMillisClaim == null) {
            log.warn("Rejected access token with missing iat_millis claim");
            throw new JwtValidationException("Missing required iat_millis claim");
        }

        if (!(iatMillisClaim instanceof Number num)) {
            log.warn("Rejected access token with malformed or non-numeric iat_millis claim: [{}]", iatMillisClaim);
            throw new JwtValidationException("Malformed or non-numeric iat_millis claim: " + iatMillisClaim);
        }

        long epochMilli = num.longValue();
        if (epochMilli <= 0) {
            log.warn("Rejected access token with invalid non-positive iat_millis: [{}]", epochMilli);
            throw new JwtValidationException("Invalid iat_millis claim value: must be positive");
        }

        long iatSecondsFromIat = iatDate.getTime() / 1000L;
        long iatSecondsFromMillis = epochMilli / 1000L;
        if (iatSecondsFromIat != iatSecondsFromMillis) {
            log.warn("Rejected access token with contradictory iat [{}] and iat_millis [{}]", iatSecondsFromIat, epochMilli);
            throw new JwtValidationException("Inconsistent iat and iat_millis claims: iat second ["
                    + iatSecondsFromIat + "] does not match iat_millis second [" + iatSecondsFromMillis + "]");
        }

        Instant issuedAtInstant = Instant.ofEpochMilli(epochMilli);
        Instant maxAllowedFuture = clock.instant().plusSeconds(jwtProperties.getClockSkewSeconds());
        if (issuedAtInstant.isAfter(maxAllowedFuture)) {
            log.warn("Rejected access token issued in the future: [{}] exceeds clock skew bound [{}]",
                    issuedAtInstant, maxAllowedFuture);
            throw new JwtValidationException("Token issued in the future beyond allowed clock skew: " + issuedAtInstant);
        }

        if (!expDate.toInstant().isAfter(issuedAtInstant)) {
            throw new JwtValidationException("Token expiration must be strictly after issued-at time");
        }

        String kid = header.getKeyId();
        String audience = payload.getAudience() != null && !payload.getAudience().isEmpty()
                ? payload.getAudience().iterator().next()
                : null;

        return new StaffJwtClaims(
                staffUserId,
                payload.getIssuer(),
                audience,
                issuedAtInstant,
                nbfDate.toInstant(),
                expDate.toInstant(),
                jti,
                ACCESS_TOKEN_TYPE,
                kid
        );
    }

    public JwtProperties getJwtProperties() {
        return jwtProperties;
    }

    public Clock getClock() {
        return clock;
    }
}
