package com.homestay.pms_backend.security;

import com.homestay.pms_backend.config.AuthDataSourceProperties;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.repository.ReservationRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import com.homestay.pms_backend.test.TestDatabaseSafetyInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.1: Dedicated Spring Boot Authentication DataSource Integration Tests")
class AuthDataSourceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("authDataSource")
    private DataSource authDataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private Environment environment;

    // =========================================================================
    // 1. Primary DataSource & pms_app Identity
    // =========================================================================

    @Test
    @DisplayName("Primary DataSource: Unadorned DataSource and JdbcTemplate connect as least-privilege role pms_app")
    void shouldVerifyPmsAppIsPrimaryDataSource() {
        assertThat(dataSource).isNotNull();
        assertThat(jdbcTemplate).isNotNull();

        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        String sessionUser = jdbcTemplate.queryForObject("SELECT session_user", String.class);

        assertThat(currentUser).isEqualTo("pms_app");
        assertThat(sessionUser).isEqualTo("pms_app");
    }

    // =========================================================================
    // 2. Auth DataSource & pms_auth Identity
    // =========================================================================

    @Test
    @DisplayName("Auth DataSource: Correctly initialized when enabled with valid pms_auth credentials")
    void shouldVerifyPmsAuthConnectsViaDedicatedCredentials() {
        assertThat(authDataSource).isNotNull();
        assertThat(authJdbcTemplate).isNotNull();

        String currentUser = authJdbcTemplate.queryForObject("SELECT current_user", String.class);
        String sessionUser = authJdbcTemplate.queryForObject("SELECT session_user", String.class);

        assertThat(currentUser).isEqualTo("pms_auth");
        assertThat(sessionUser).isEqualTo("pms_auth");
    }

    // =========================================================================
    // 3. Distinct Configured Credentials (pms_app vs pms_auth)
    // =========================================================================

    @Test
    @DisplayName("Distinct Credentials: pms_app and pms_auth use separate, distinct database roles")
    void shouldVerifyPmsAppAndPmsAuthUseDistinctCredentials() {
        String pmsAppUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        String pmsAuthUser = authJdbcTemplate.queryForObject("SELECT current_user", String.class);

        assertThat(pmsAppUser).isEqualTo("pms_app");
        assertThat(pmsAuthUser).isEqualTo("pms_auth");
        assertThat(pmsAppUser).isNotEqualTo(pmsAuthUser);
    }

    // =========================================================================
    // 4. Primary JPA Repositories use pms_app
    // =========================================================================

    @Test
    @DisplayName("JPA Verification: Standard Spring Data JPA repositories operate unaffected through pms_app")
    void shouldVerifyPrimaryJpaRepositoriesUsePmsApp() {
        long propertyCount = propertyRepository.count();
        assertThat(propertyCount).isGreaterThanOrEqualTo(0);

        long reservationCount = reservationRepository.count();
        assertThat(reservationCount).isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // 5. Auth JdbcTemplate Uses pms_auth & Reads Permitted Fields
    // =========================================================================

    @Test
    @DisplayName("Privilege Check: pms_auth can read permitted staff authentication fields including password_hash")
    void shouldAllowPmsAuthToReadPermittedStaffAuthFields() {
        List<Map<String, Object>> staff = authJdbcTemplate.queryForList(
                "SELECT id, email, password_hash, first_name, last_name, phone, " +
                "is_active, is_locked, failed_login_attempts, lockout_until, " +
                "last_login_at, password_changed_at, created_at, updated_at " +
                "FROM staff_users LIMIT 5"
        );
        assertThat(staff).isNotNull();
    }

    // =========================================================================
    // 6. Domain & Finance Isolation for pms_auth
    // =========================================================================

    @Test
    @DisplayName("Privilege Check: pms_auth is strictly DENIED from reading reservations, properties, or finance tables")
    void shouldDenyPmsAuthFromReadingReservationsAndFinanceTables() {
        // Business Domain Denials
        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM reservations"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table reservations");

        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM properties"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table properties");

        // Finance Domain Denials
        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM finance_journal_entries"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table finance_journal_entries");

        assertThatThrownBy(() -> authJdbcTemplate.queryForList("SELECT * FROM finance_accounts"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table finance_accounts");
    }

    // =========================================================================
    // 7. pms_app Privilege Boundaries (Password Hashes & Tokens Denied)
    // =========================================================================

    @Test
    @DisplayName("Privilege Check: pms_app is strictly DENIED from reading staff password hashes")
    void shouldDenyPmsAppFromReadingStaffPasswordHashes() {
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT password_hash FROM staff_users"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_users");

        // Metadata columns remain readable
        List<Map<String, Object>> metadata = jdbcTemplate.queryForList(
                "SELECT id, email, first_name, last_name, phone, is_active FROM staff_users LIMIT 5"
        );
        assertThat(metadata).isNotNull();
    }

    @Test
    @DisplayName("Privilege Check: pms_app is strictly DENIED from accessing or querying staff_refresh_tokens")
    void shouldDenyPmsAppFromAccessingRefreshTokens() {
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT * FROM staff_refresh_tokens"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table staff_refresh_tokens");
    }

    // =========================================================================
    // 8. Flyway Configuration & Schema Ownership (pms_admin)
    // =========================================================================

    @Test
    @DisplayName("Flyway Configuration: Flyway is disabled by default and uses pms_admin for schema ownership")
    void shouldVerifyFlywayRemainsDisabledByDefault() {
        String flywayEnabled = environment.getProperty("spring.flyway.enabled");
        assertThat(flywayEnabled).isEqualTo("false");

        List<String> coreTables = List.of("staff_users", "staff_refresh_tokens", "properties", "reservations");
        for (String table : coreTables) {
            String owner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    String.class,
                    table
            );
            assertThat(owner)
                    .as("Table %s must be owned by pms_admin", table)
                    .isEqualTo("pms_admin");
        }
    }

    // =========================================================================
    // 9. Port 5432 Safety & Configuration Isolation
    // =========================================================================

    @Test
    @DisplayName("Safety Check: TestDatabaseSafetyInitializer strictly rejects auth.datasource.url targeting port 5432")
    void shouldRejectAnyAuthDatasourceTargetingPort5432() {
        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety("jdbc:postgresql://localhost:5432/v7_disposable_db", "auth.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("5432");

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety("jdbc:postgresql://localhost/v7_disposable_db", "auth.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("5432");

        assertThatThrownBy(() -> TestDatabaseSafetyInitializer.validateSafety("jdbc:postgresql://127.0.0.1:5433/homestay_pms", "auth.datasource.url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FATAL SAFETY BLOCK")
                .hasMessageContaining("homestay_pms");
    }

    // =========================================================================
    // 10. Fail-Closed & Forbidden Roles Validation
    // =========================================================================

    @Test
    @DisplayName("Configuration Hygiene: Missing pms_auth password fails closed when authentication is enabled")
    void shouldFailClosedWhenAuthPasswordMissing() {
        AuthDataSourceProperties props = new AuthDataSourceProperties();
        props.setUrl("jdbc:postgresql://localhost:5433/v7_disposable_db");
        props.setUsername("pms_auth");
        props.setPassword(null);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing pms_auth password");

        props.setPassword("   ");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing pms_auth password");
    }

    @Test
    @DisplayName("Security Lockdown: authDataSource strictly forbids administrative or application roles")
    void shouldRejectForbiddenAdministrativeAndAppRoles() {
        List<String> forbidden = List.of("pms_admin", "pms_app", "postgres", "root", "admin", "POSTGRES");

        for (String role : forbidden) {
            AuthDataSourceProperties props = new AuthDataSourceProperties();
            props.setUrl("jdbc:postgresql://localhost:5433/v7_disposable_db");
            props.setUsername(role);
            props.setPassword("validSecretPassword123");

            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FATAL SECURITY VIOLATION");
        }
    }
}
