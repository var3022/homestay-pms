package com.homestay.pms_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Configuration properties for the dedicated pms_auth runtime DataSource.
 * Enforces strict credential hygiene, rejects administrative or application roles,
 * and fails closed when authentication credentials are missing.
 */
@ConfigurationProperties(prefix = "auth.datasource")
public class AuthDataSourceProperties {

    private static final Set<String> FORBIDDEN_USERS = Set.of(
            "pms_admin",
            "pms_app",
            "postgres",
            "root",
            "admin"
    );

    private boolean enabled = true;
    private String url;
    private String username = "pms_auth";
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 5;
    private int minimumIdle = 1;
    private long connectionTimeout = 10000;
    private long idleTimeout = 300000;
    private long maxLifetime = 1800000;

    public void validate() {
        if (!enabled) {
            return;
        }

        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "FATAL CONFIGURATION ERROR: auth.datasource.url must not be null or blank when authentication is enabled."
            );
        }

        if (!url.trim().startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "FATAL CONFIGURATION ERROR: auth.datasource.url must be a valid PostgreSQL JDBC URL."
            );
        }

        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "FATAL CONFIGURATION ERROR: auth.datasource.username must not be null or blank."
            );
        }

        String normalizedUser = username.trim().toLowerCase();
        if (FORBIDDEN_USERS.contains(normalizedUser)) {
            throw new IllegalStateException(
                    "FATAL SECURITY VIOLATION: authDataSource cannot use administrative or application role '" + username +
                    "'. Dedicated 'pms_auth' credentials are required. No silent fallback permitted."
            );
        }

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "FATAL CONFIGURATION ERROR: Missing pms_auth password! Credentials must be supplied via AUTH_DATASOURCE_PASSWORD. " +
                    "Missing pms_auth configuration must fail closed when authentication is enabled."
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public long getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public long getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public long getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
    }
}
