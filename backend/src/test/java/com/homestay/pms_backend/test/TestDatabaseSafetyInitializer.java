package com.homestay.pms_backend.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces strict database safety, role segregation, and credential hygiene in automated test environments:
 * 1. Strictly rejects any connection to port 5432 (both application datasource and Flyway).
 * 2. Strictly rejects any connection to the production-like database 'homestay_pms' on localhost.
 * 3. Enforces that test database credentials (TEST_DATASOURCE_PASSWORD) are explicitly provided without hardcoded fallbacks.
 * 4. When Flyway is enabled, verifies separate Flyway credentials (TEST_FLYWAY_PASSWORD) and target safety.
 * 5. Verifies that the designated isolated database (e.g. port 5433) is reachable before Spring context initializes.
 * 6. Fails fast with descriptive errors if isolation cannot be guaranteed, preventing any silent fallback.
 * 7. Sanitizes log output so database credentials are never exposed in test output.
 */
public class TestDatabaseSafetyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseSafetyInitializer.class);

    public static final int FORBIDDEN_PORT = 5432;
    public static final String FORBIDDEN_DB = "homestay_pms";
    private static final Pattern JDBC_POSTGRES_PATTERN = Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/(.+)");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();

        String datasourceUrl = env.getProperty("spring.datasource.url");
        String flywayUrl = env.getProperty("spring.flyway.url");
        String datasourcePassword = env.getProperty("spring.datasource.password");
        String flywayEnabled = env.getProperty("spring.flyway.enabled");

        validateSafety(datasourceUrl, "spring.datasource.url");

        if (flywayUrl != null && !flywayUrl.isBlank()) {
            validateSafety(flywayUrl, "spring.flyway.url");
        }

        validateCredentials(datasourcePassword, "spring.datasource.password");

        if ("true".equalsIgnoreCase(flywayEnabled)) {
            String flywayPassword = env.getProperty("spring.flyway.password");
            validateCredentials(flywayPassword, "spring.flyway.password");
            if (flywayUrl != null && !flywayUrl.isBlank()) {
                validateConnectivity(flywayUrl);
            }
        }

        validateConnectivity(datasourceUrl);

        // Dedicated Authentication Datasource safety checks
        String authEnabled = env.getProperty("auth.datasource.enabled");
        String authUrl = env.getProperty("auth.datasource.url");
        if (!"false".equalsIgnoreCase(authEnabled) && authUrl != null && !authUrl.isBlank()) {
            validateSafety(authUrl, "auth.datasource.url");
            String authPassword = env.getProperty("auth.datasource.password");
            validateCredentials(authPassword, "auth.datasource.password");
            validateConnectivity(authUrl);
        }

        log.info("Test database safety checks passed. Target isolated datasource verified: {}", sanitizeUrl(datasourceUrl));
    }

    public static void validateSafety(String url, String propertyName) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("FATAL SAFETY BLOCK: " + propertyName + " must not be null or blank in test environment.");
        }

        Matcher matcher = JDBC_POSTGRES_PATTERN.matcher(url.trim());
        if (!matcher.find()) {
            throw new IllegalStateException(
                "FATAL SAFETY BLOCK: Unrecognized or non-PostgreSQL JDBC destination in " + propertyName +
                " [" + sanitizeUrl(url) + "]. Tests require an isolated PostgreSQL instance."
            );
        }

        String host = matcher.group(1);
        String portStr = matcher.group(2);
        int port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 5432;
        String rawDb = matcher.group(3);
        String dbName = rawDb.contains("?") ? rawDb.substring(0, rawDb.indexOf('?')) : rawDb;

        // Rule 1: Strictly reject port 5432
        if (port == FORBIDDEN_PORT) {
            throw new IllegalStateException(
                "FATAL SAFETY BLOCK: Automated tests are strictly prohibited from connecting to port " +
                FORBIDDEN_PORT + "! Detected unsafe destination in " + propertyName +
                " [host=" + host + ", port=" + port + ", database=" + dbName + "]. Aborting test execution."
            );
        }

        // Rule 2: Strictly reject target database named 'homestay_pms' on localhost / loopback
        if (FORBIDDEN_DB.equalsIgnoreCase(dbName) && isLoopbackOrLocalhost(host)) {
            throw new IllegalStateException(
                "FATAL SAFETY BLOCK: Automated tests are strictly prohibited from connecting to production PMS database '" +
                FORBIDDEN_DB + "' on " + host + "! Aborting test execution."
            );
        }
    }

    public static void validateCredentials(String password, String propertyName) {
        if (password == null || password.isBlank()) {
            String envVar = "TEST_DATASOURCE_PASSWORD";
            if ("spring.flyway.password".equals(propertyName)) {
                envVar = "TEST_FLYWAY_PASSWORD";
            } else if ("auth.datasource.password".equals(propertyName)) {
                envVar = "TEST_AUTH_DATASOURCE_PASSWORD";
            }
            throw new IllegalStateException(
                "FATAL CONFIGURATION ERROR: Test database credentials are not configured! Missing " +
                envVar + " environment variable or " + propertyName + " property."
            );
        }
    }

    public static void validateConnectivity(String url) {
        Matcher matcher = JDBC_POSTGRES_PATTERN.matcher(url.trim());
        if (!matcher.find()) {
            throw new IllegalStateException("SAFETY BLOCK: Invalid JDBC destination for connectivity check: " + sanitizeUrl(url));
        }

        String host = matcher.group(1);
        String portStr = matcher.group(2);
        int port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 5432;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
        } catch (Exception e) {
            throw new IllegalStateException(
                "ISOLATION UNAVAILABLE: Isolated test database at " + host + ":" + port +
                " is not reachable (" + e.getClass().getSimpleName() + ": " + e.getMessage() +
                "). Automated tests aborted safely to prevent unsafe fallback."
            );
        }
    }

    public static String sanitizeUrl(String url) {
        if (url == null) {
            return "null";
        }
        return url.replaceAll("://[^/@]+@", "://***:***@");
    }

    private static boolean isLoopbackOrLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "0.0.0.0".equals(host);
    }
}
