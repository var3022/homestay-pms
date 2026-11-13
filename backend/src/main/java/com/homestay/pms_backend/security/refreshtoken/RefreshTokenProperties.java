package com.homestay.pms_backend.security.refreshtoken;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the refresh-token lifecycle.
 */
@ConfigurationProperties(prefix = "auth.refresh-token")
public class RefreshTokenProperties {

    /**
     * Refresh token validity duration in days (default: 7 days).
     */
    private int ttlDays = 7;

    public int getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(int ttlDays) {
        this.ttlDays = ttlDays;
    }
}
