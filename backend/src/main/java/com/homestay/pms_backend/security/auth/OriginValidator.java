package com.homestay.pms_backend.security.auth;

import com.homestay.pms_backend.config.AuthOriginProperties;
import com.homestay.pms_backend.exception.OriginValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validates inbound request Origin headers against strict allowlists to protect cookie-authenticated
 * endpoints against Cross-Site Request Forgery (CSRF).
 *
 * Implements strict defense-in-depth:
 * 1. Requires explicit allowed Origin matching configured application origins.
 * 2. Rejects untrusted, malformed, or suffix-spoofed origins.
 * 3. Rejects absent Origin headers unless verified under a trusted-server request policy.
 * 4. Normalizes origins (scheme and hostname lowercase, default port stripped) for exact set lookup.
 * 5. Leverages Sec-Fetch-Site as defense-in-depth.
 * 6. Uses constant-time comparison on internal server authentication tokens to prevent timing attacks.
 */
@Component
public class OriginValidator {

    private static final Logger log = LoggerFactory.getLogger(OriginValidator.class);

    private final Set<String> allowedOrigins;
    private final AuthOriginProperties properties;
    private final Set<String> trustedProxies;

    public OriginValidator(
            AuthOriginProperties properties,
            ClientContextExtractor clientContextExtractor
    ) {
        this.properties = properties;
        Set<String> normalized = new HashSet<>();
        if (properties.getAllowedOrigins() != null) {
            for (String origin : properties.getAllowedOrigins()) {
                String clean = normalizeOrigin(origin);
                if (clean != null) {
                    normalized.add(clean);
                }
            }
        }
        this.allowedOrigins = Collections.unmodifiableSet(normalized);
        this.trustedProxies = clientContextExtractor.getTrustedProxies();
    }

    /**
     * Validates that the request comes from a trusted origin or verified server policy.
     *
     * @param request Inbound servlet request
     * @throws OriginValidationException if origin is missing, malformed, or not allowed
     */
    public void validate(HttpServletRequest request) {
        if (request == null) {
            throw new OriginValidationException("HTTP request is null");
        }

        String origin = request.getHeader("Origin");
        String secFetchSite = request.getHeader("Sec-Fetch-Site");

        if (origin != null && !origin.isBlank()) {
            String normalizedOrigin = normalizeOrigin(origin);
            if (normalizedOrigin == null) {
                log.warn("Origin validation rejected: malformed or unparseable Origin header [{}]", origin);
                throw new OriginValidationException("Malformed Origin header");
            }

            if (!allowedOrigins.contains(normalizedOrigin)) {
                log.warn("Origin validation rejected: untrusted origin [{}] not in allowed origins {}",
                        origin, allowedOrigins);
                throw new OriginValidationException("Origin not allowed: " + origin);
            }

            // Defense in depth: log when cross-site fetch metadata is declared
            if (secFetchSite != null && secFetchSite.equalsIgnoreCase("cross-site")) {
                log.debug("Sec-Fetch-Site indicates cross-site request from allowed origin [{}]", normalizedOrigin);
            }

            return;
        }

        // Handle absent Origin header
        if (properties.isAllowTrustedServerRequests()) {
            String remoteAddr = request.getRemoteAddr();
            boolean isLoopbackOrTrustedProxy = isLoopback(remoteAddr) || trustedProxies.contains(remoteAddr);
            String internalHeader = request.getHeader(properties.getTrustedServerHeader());
            String expectedToken = properties.getTrustedServerToken();

            boolean hasValidToken = false;
            if (internalHeader != null && expectedToken != null && !expectedToken.isBlank()) {
                byte[] a = internalHeader.getBytes(StandardCharsets.UTF_8);
                byte[] b = expectedToken.getBytes(StandardCharsets.UTF_8);
                hasValidToken = MessageDigest.isEqual(a, b);
            }

            if (isLoopbackOrTrustedProxy && hasValidToken) {
                log.debug("Accepted absent Origin under verified trusted-server policy from [{}]", remoteAddr);
                return;
            }

            log.warn("Trusted-server policy rejected request without Origin: remoteAddr [{}], trusted proxy [{}], valid header [{}]",
                    remoteAddr, isLoopbackOrTrustedProxy, hasValidToken);
            throw new OriginValidationException("Request rejected: caller does not satisfy trusted-server policy");
        }

        log.warn("Origin validation rejected: missing Origin header");
        throw new OriginValidationException("Missing required Origin header");
    }

    /**
     * Normalizes an origin string according to RFC 6454.
     * Scheme and host are converted to lowercase. Default HTTP (80) and HTTPS (443) ports are stripped.
     */
    public static String normalizeOrigin(String rawOrigin) {
        if (rawOrigin == null || rawOrigin.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(rawOrigin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();

            if (scheme == null || host == null) {
                return null;
            }

            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);

            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }

            boolean isDefaultPort = ("http".equals(scheme) && (port == 80 || port == -1))
                    || ("https".equals(scheme) && (port == 443 || port == -1));

            if (isDefaultPort) {
                return scheme + "://" + host;
            } else {
                return scheme + "://" + host + ":" + port;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isLoopback(String addr) {
        return "127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr);
    }

    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}
