package com.homestay.pms_backend.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Test Database Safety Initializer - Unit Tests")
class TestDatabaseSafetyInitializerTest {

    @Test
    @DisplayName("Should strictly reject JDBC destination pointing to forbidden port 5432")
    void shouldRejectPort5432() {
        String unsafeUrl = "jdbc:postgresql://localhost:5432/any_test_db";

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety(unsafeUrl, "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("5432");
    }

    @Test
    @DisplayName("Should strictly reject JDBC destination with default implicit port 5432")
    void shouldRejectImplicitPort5432() {
        String unsafeUrl = "jdbc:postgresql://localhost/v7_disposable_db";

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety(unsafeUrl, "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("5432");
    }

    @Test
    @DisplayName("Should strictly reject production database name 'homestay_pms' on localhost")
    void shouldRejectProductionDbNameOnLocalhost() {
        String unsafeUrl = "jdbc:postgresql://127.0.0.1:5433/homestay_pms";

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety(unsafeUrl, "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("homestay_pms");
    }

    @Test
    @DisplayName("Should reject null or blank JDBC destination")
    void shouldRejectNullOrBlankUrl() {
        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety(null, "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be null or blank");

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety("   ", "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    @DisplayName("Should reject non-PostgreSQL JDBC destination")
    void shouldRejectNonPostgreSqlUrl() {
        String h2Url = "jdbc:h2:mem:testdb";

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety(h2Url, "spring.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized or non-PostgreSQL JDBC destination");
    }

    @Test
    @DisplayName("Should accept valid isolated destination on port 5433")
    void shouldAcceptSafeIsolatedPort5433() {
        String safeUrl = "jdbc:postgresql://localhost:5433/v7_disposable_db";

        assertThatCode(() -> TestDatabaseSafetyInitializer.validateSafety(safeUrl, "spring.datasource.url"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should strictly reject missing or blank database password")
    void shouldRejectMissingPassword() {
        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateCredentials(null, "spring.datasource.password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL CONFIGURATION ERROR")
                .hasMessageContaining("TEST_DATASOURCE_PASSWORD");

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateCredentials("   ", "spring.datasource.password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL CONFIGURATION ERROR")
                .hasMessageContaining("TEST_DATASOURCE_PASSWORD");
    }

    @Test
    @DisplayName("Should strictly reject missing or blank auth database password mentioning TEST_AUTH_DATASOURCE_PASSWORD")
    void shouldRejectMissingAuthPassword() {
        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateCredentials(null, "auth.datasource.password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL CONFIGURATION ERROR")
                .hasMessageContaining("TEST_AUTH_DATASOURCE_PASSWORD");

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateCredentials("   ", "auth.datasource.password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL CONFIGURATION ERROR")
                .hasMessageContaining("TEST_AUTH_DATASOURCE_PASSWORD");
    }

    @Test
    @DisplayName("Should accept valid non-blank database password")
    void shouldAcceptValidPassword() {
        assertThatCode(() -> TestDatabaseSafetyInitializer.validateCredentials("secureTestPass123", "spring.datasource.password"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Connectivity check should fail fast with ISOLATION UNAVAILABLE when target port is unreachable")
    void shouldFailFastWhenUnreachable() {
        // Port 59999 should not have a listening PostgreSQL service
        String unreachableUrl = "jdbc:postgresql://127.0.0.1:59999/v7_disposable_db";

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateConnectivity(unreachableUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISOLATION UNAVAILABLE")
                .hasMessageContaining("59999");
    }

    @Test
    @DisplayName("Should sanitize credentials from URLs so secrets are never logged")
    void shouldSanitizeCredentials() {
        String urlWithCreds = "jdbc:postgresql://pms_admin:superSecret123@localhost:5433/v7_disposable_db";
        String sanitized = TestDatabaseSafetyInitializer.sanitizeUrl(urlWithCreds);

        assertThat(sanitized).doesNotContain("pms_admin", "superSecret123");
        assertThat(sanitized).contains("***:***@localhost:5433");
    }
}
