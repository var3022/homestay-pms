package com.homestay.pms_backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Origin and CSRF defense on authentication endpoints.
 *
 * Implements strict allowlist verification:
 * - allowedOrigins: list of trusted application origins (e.g., http://localhost:3000)
 * - allowTrustedServerRequests: whether absent Origin requests are permitted via trusted server policy (disabled by default)
 * - trustedServerHeader: internal header name for trusted proxy requests
 * - trustedServerToken: secret value for trusted server verification (minimum 32 characters when enabled)
 */
@ConfigurationProperties(prefix = "auth.origin")
public class AuthOriginProperties {

    private List<String> allowedOrigins = new ArrayList<>();
    private boolean allowTrustedServerRequests = false;
    private String trustedServerHeader = "X-PMS-Internal-Source";
    private String trustedServerToken = "";

    @PostConstruct
    public void validate() {
        if (allowTrustedServerRequests) {
            if (trustedServerToken == null || trustedServerToken.isBlank() || trustedServerToken.length() < 32) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: auth.origin.allow-trusted-server-requests is enabled " +
                        "but auth.origin.trusted-server-token is missing, blank, or fewer than 32 characters."
                );
            }
            if ("pms-internal-proxy".equals(trustedServerToken)) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Default placeholder token cannot be used when " +
                        "auth.origin.allow-trusted-server-requests is enabled."
                );
            }
            if (trustedServerHeader == null || trustedServerHeader.isBlank()) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: auth.origin.trusted-server-header must not be null or blank."
                );
            }
        }
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public boolean isAllowTrustedServerRequests() {
        return allowTrustedServerRequests;
    }

    public void setAllowTrustedServerRequests(boolean allowTrustedServerRequests) {
        this.allowTrustedServerRequests = allowTrustedServerRequests;
    }

    public String getTrustedServerHeader() {
        return trustedServerHeader;
    }

    public void setTrustedServerHeader(String trustedServerHeader) {
        this.trustedServerHeader = trustedServerHeader;
    }

    public String getTrustedServerToken() {
        return trustedServerToken;
    }

    public void setTrustedServerToken(String trustedServerToken) {
        this.trustedServerToken = trustedServerToken;
    }
}
