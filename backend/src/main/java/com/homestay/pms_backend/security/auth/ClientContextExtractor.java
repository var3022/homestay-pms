package com.homestay.pms_backend.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts and sanitizes client IP and User-Agent headers for database audit logging.
 *
 * Implements strict security boundaries:
 * 1. Default zero-trust for X-Forwarded-For: only inspects forwarding headers when the direct
 *    socket remote address belongs to an explicitly configured trusted reverse proxy.
 * 2. Bounds and sanitizes IP length (max 50 chars) and User-Agent length (max 255 chars)
 *    to prevent database constraint violations or log-injection vectors.
 * 3. Strips control characters from headers.
 */
@Component
public class ClientContextExtractor {

    private static final int MAX_IP_LENGTH = 50;
    private static final int MAX_USER_AGENT_LENGTH = 255;
    private static final String UNKNOWN_USER_AGENT = "Unknown";

    // Validates basic IPv4 and IPv6 string syntax without triggering DNS lookups
    private static final Pattern IP_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final Set<String> trustedProxies;

    public ClientContextExtractor(
            @Value("${auth.proxy.trusted-proxies:}") String trustedProxiesConfig
    ) {
        if (trustedProxiesConfig != null && !trustedProxiesConfig.isBlank()) {
            this.trustedProxies = Arrays.stream(trustedProxiesConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
        } else {
            this.trustedProxies = Set.of();
        }
    }

    /**
     * Extracts safe, bounded client context from the HTTP request.
     *
     * @param request Inbound HTTP servlet request
     * @return ClientContext with verified IP and sanitized User-Agent
     */
    public ClientContext extract(HttpServletRequest request) {
        if (request == null) {
            return new ClientContext(null, UNKNOWN_USER_AGENT);
        }

        String remoteAddr = request.getRemoteAddr();
        String clientIp = remoteAddr;

        // Only trust X-Forwarded-For if remoteAddr is explicitly within trustedProxies
        if (remoteAddr != null && trustedProxies.contains(remoteAddr.trim())) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String firstIp = forwardedFor.split(",")[0].trim();
                if (!firstIp.isBlank() && isValidIpFormat(firstIp)) {
                    clientIp = firstIp;
                }
            }
        }

        clientIp = sanitizeAndBoundIp(clientIp);
        String userAgent = sanitizeAndBoundUserAgent(request.getHeader(HttpHeaders.USER_AGENT));

        return new ClientContext(clientIp, userAgent);
    }

    private boolean isValidIpFormat(String ip) {
        return ip.length() <= MAX_IP_LENGTH && IP_PATTERN.matcher(ip).matches();
    }

    private String sanitizeAndBoundIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String sanitized = ip.trim();
        if (sanitized.length() > MAX_IP_LENGTH) {
            sanitized = sanitized.substring(0, MAX_IP_LENGTH);
        }
        return sanitized;
    }

    private String sanitizeAndBoundUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_USER_AGENT;
        }
        // Strip ASCII control characters except standard whitespace
        String sanitized = userAgent.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        if (sanitized.isBlank()) {
            return UNKNOWN_USER_AGENT;
        }
        if (sanitized.length() > MAX_USER_AGENT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_USER_AGENT_LENGTH);
        }
        return sanitized;
    }

    public Set<String> getTrustedProxies() {
        return java.util.Collections.unmodifiableSet(trustedProxies);
    }
}
