package com.homestay.pms_backend.security.refreshtoken;

import com.homestay.pms_backend.config.AuthCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages secure HttpOnly refresh-token cookies.
 *
 * Enforces production security standards:
 * - Reads tokens strictly from HttpOnly cookies (never query params, headers, or body)
 * - Validates cookie presence, length, format, and duplication
 * - Sets SameSite=Strict, Path=/, and Max-Age matching configured TTL
 * - Deletes cookies using identical attributes and Max-Age=0
 * - In production: uses __Host- prefix and Secure=true with no Domain
 */
@Component
public class AuthCookieManager {

    private static final Logger log = LoggerFactory.getLogger(AuthCookieManager.class);

    private final AuthCookieProperties cookieProperties;
    private final RefreshTokenGenerator tokenGenerator;

    public AuthCookieManager(AuthCookieProperties cookieProperties, RefreshTokenGenerator tokenGenerator) {
        this.cookieProperties = cookieProperties;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * Creates a new HttpOnly replacement cookie containing the rotated refresh token.
     */
    public ResponseCookie createRefreshCookie(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Raw refresh token must not be null or empty");
        }
        return ResponseCookie.from(cookieProperties.getCookieName(), rawToken)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .path(cookieProperties.getPath())
                .sameSite(cookieProperties.getSameSite())
                .maxAge(Duration.ofDays(cookieProperties.getTtlDays()))
                .build();
    }

    /**
     * Creates an expired cookie to clear the client refresh cookie.
     */
    public ResponseCookie createRemovalCookie() {
        return ResponseCookie.from(cookieProperties.getCookieName(), "")
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .path(cookieProperties.getPath())
                .sameSite(cookieProperties.getSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }

    /**
     * Extracts and validates the raw refresh token strictly from the request HttpOnly cookie.
     *
     * @param request Inbound servlet request
     * @return Validated raw refresh token string
     * @throws InvalidRefreshTokenException if cookie is missing, duplicated, or malformed
     */
    public String extractRefreshToken(HttpServletRequest request) {
        if (request == null) {
            throw new InvalidRefreshTokenException("HTTP request is null");
        }

        String cookieName = cookieProperties.getCookieName();
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            log.warn("Rejected refresh request: no cookies present in request");
            throw new InvalidRefreshTokenException("Missing refresh token cookie");
        }

        List<Cookie> matchedCookies = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                matchedCookies.add(cookie);
            }
        }

        if (matchedCookies.isEmpty()) {
            log.warn("Rejected refresh request: cookie [{}] not found", cookieName);
            throw new InvalidRefreshTokenException("Missing refresh token cookie");
        }

        if (matchedCookies.size() > 1) {
            log.warn("Rejected refresh request: duplicate [{}] cookies detected (count={})",
                    cookieName, matchedCookies.size());
            throw new InvalidRefreshTokenException("Duplicate refresh token cookies detected");
        }

        String rawToken = matchedCookies.get(0).getValue();
        if (rawToken == null || rawToken.isBlank()) {
            log.warn("Rejected refresh request: cookie [{}] has empty value", cookieName);
            throw new InvalidRefreshTokenException("Empty refresh token cookie");
        }

        if (!tokenGenerator.isValidTokenFormat(rawToken)) {
            log.warn("Rejected refresh request: cookie [{}] format is invalid or malformed", cookieName);
            throw new InvalidRefreshTokenException("Malformed refresh token format");
        }

        return rawToken;
    }

    /**
     * Optionally extracts the refresh token without throwing for missing/empty cookies (for idempotent logout).
     * Rejects duplicate or malformed cookies gracefully.
     */
    public Optional<String> extractOptionalRefreshToken(HttpServletRequest request) {
        try {
            return Optional.of(extractRefreshToken(request));
        } catch (InvalidRefreshTokenException e) {
            return Optional.empty();
        }
    }

    public AuthCookieProperties getProperties() {
        return cookieProperties;
    }
}
