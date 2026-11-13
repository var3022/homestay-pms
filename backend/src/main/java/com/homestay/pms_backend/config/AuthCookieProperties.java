package com.homestay.pms_backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Configuration properties for refresh-token HTTP cookies.
 *
 * Implements strict separation between Production and Development:
 * - Production: secure=true, name="__Host-pms-refresh", sameSite="Strict", path="/"
 * - Development: secure=false, name="pms-refresh-dev", sameSite="Strict", path="/"
 *
 * Security and RFC 6265bis enforcement:
 * 1. When the production Spring profile ('prod' or 'production') is active, Secure=true is strictly required
 *    and auth.cookie.allow-insecure-dev-mode=true is rejected on startup. Production deployments must activate
 *    the production profile ('prod' or 'production').
 * 2. In non-production environments, insecure cookies (secure=false) are rejected unless allowInsecureDevMode=true
 *    is explicitly configured for local development/testing.
 * 3. Cookies with __Host- prefix must have secure=true.
 * 4. Production cookies (secure=true) must use the __Host- prefix.
 * 5. SameSite must be "Strict".
 * 6. Path must be "/".
 */
@ConfigurationProperties(prefix = "auth.cookie")
public class AuthCookieProperties implements EnvironmentAware {

    public static final String PROD_COOKIE_NAME = "__Host-pms-refresh";
    public static final String DEV_COOKIE_NAME = "pms-refresh-dev";

    private boolean secure = true;
    private boolean allowInsecureDevMode = false;
    private String name;
    private String sameSite = "Strict";
    private String path = "/";
    private int ttlDays = 7;

    private Environment environment;

    public AuthCookieProperties(@Autowired(required = false) Environment environment) {
        this.environment = environment;
    }

    public AuthCookieProperties() {
        this(null);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean isProductionProfileActive() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = isProductionProfileActive();

        if (isProd) {
            if (!secure) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Insecure cookies (auth.cookie.secure=false) are strictly forbidden in production profile."
                );
            }
            if (allowInsecureDevMode) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: auth.cookie.allow-insecure-dev-mode cannot be enabled in production profile."
                );
            }
        }

        if (!secure) {
            if (!allowInsecureDevMode) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Insecure cookies (auth.cookie.secure=false) are strictly forbidden " +
                        "unless auth.cookie.allow-insecure-dev-mode=true is explicitly configured for local development/testing."
                );
            }
            if (name != null && name.startsWith("__Host-")) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Cookies with __Host- prefix must have secure=true."
                );
            }
            if (name == null || name.isBlank()) {
                name = DEV_COOKIE_NAME;
            }
        } else {
            if (name == null || name.isBlank()) {
                name = PROD_COOKIE_NAME;
            }
            if (!name.startsWith("__Host-")) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Production refresh cookies must use the __Host- prefix (e.g. __Host-pms-refresh)."
                );
            }
        }

        if (!"Strict".equalsIgnoreCase(sameSite)) {
            throw new IllegalStateException(
                    "FATAL SECURITY MISCONFIGURATION: auth.cookie.same-site must be 'Strict' to protect refresh cookies."
            );
        }

        if (!"/".equals(path)) {
            throw new IllegalStateException(
                    "FATAL SECURITY MISCONFIGURATION: auth.cookie.path must be '/' for __Host- cookies."
            );
        }
    }

    public String getCookieName() {
        if (name == null || name.isBlank()) {
            return secure ? PROD_COOKIE_NAME : DEV_COOKIE_NAME;
        }
        return name;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean isAllowInsecureDevMode() {
        return allowInsecureDevMode;
    }

    public void setAllowInsecureDevMode(boolean allowInsecureDevMode) {
        this.allowInsecureDevMode = allowInsecureDevMode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(int ttlDays) {
        if (ttlDays < 1 || ttlDays > 90) {
            throw new IllegalArgumentException("auth.cookie.ttl-days must be between 1 and 90 days");
        }
        this.ttlDays = ttlDays;
    }
}
