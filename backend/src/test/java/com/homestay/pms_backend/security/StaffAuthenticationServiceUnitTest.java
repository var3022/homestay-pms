package com.homestay.pms_backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit Tests: Staff Identity Lookup & Staff Authentication Service")
class StaffAuthenticationServiceUnitTest {

    private StaffIdentityLookup identityLookup;
    private PasswordEncoder passwordEncoder;
    private JdbcTemplate authJdbcTemplate;
    private TransactionTemplate authTransactionTemplate;
    private StaffAuthenticationService authService;

    @BeforeEach
    void setUp() {
        identityLookup = mock(StaffIdentityLookup.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authJdbcTemplate = mock(JdbcTemplate.class);

        // Execute TransactionCallback synchronously for unit tests
        authTransactionTemplate = mock(TransactionTemplate.class);
        when(authTransactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        authService = new StaffAuthenticationService(
                identityLookup,
                passwordEncoder,
                authJdbcTemplate,
                authTransactionTemplate
        );
    }

    @Test
    @DisplayName("Email normalization handles null, empty, mixed case, and whitespace")
    void testEmailNormalization() {
        assertThat(StaffIdentityLookup.normalizeEmail(null)).isNull();
        assertThat(StaffIdentityLookup.normalizeEmail("   ")).isEmpty();
        assertThat(StaffIdentityLookup.normalizeEmail("  USER@Example.COM  ")).isEqualTo("user@example.com");
        assertThat(StaffIdentityLookup.normalizeEmail("admin@homestay.com")).isEqualTo("admin@homestay.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-email", "missing-at-sign.com", "@domain.com", "domain.com@"})
    @DisplayName("Invalid email formats reject authentication and execute dummy BCrypt comparison")
    void testInvalidEmailRejection(String invalidEmail) {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(invalidEmail, "ValidPass#123", "127.0.0.1", "UnitTest"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        verify(passwordEncoder).matches(eq("ValidPass#123"), anyString());
        verifyNoInteractions(identityLookup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Null or blank passwords reject authentication and execute dummy BCrypt comparison")
    void testBlankPasswordRejection(String blankPassword) {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("valid@homestay.com", blankPassword, "127.0.0.1", "UnitTest"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        verify(passwordEncoder).matches(eq("invalid_candidate"), anyString());
        verifyNoInteractions(identityLookup);
    }

    @Test
    @DisplayName("Nonexistent user triggers dummy match and database login failure attempt")
    void testNonexistentUserDummyMatchAndAttempt() {
        when(identityLookup.findByEmail("ghost@homestay.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(authJdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("ghost@homestay.com", "SecretPass123", "10.0.0.1", "Agent"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        verify(passwordEncoder).matches(eq("SecretPass123"), anyString());
        verify(authJdbcTemplate).queryForObject(
                contains("fn_record_staff_login_attempt"),
                eq(Boolean.class),
                eq("ghost@homestay.com"),
                eq("10.0.0.1"),
                eq("Agent")
        );
    }

    @Test
    @DisplayName("Database rejection of login success throws generic StaffAuthenticationException")
    void testDatabaseRejectingLoginSuccess() {
        UUID userId = UUID.randomUUID();
        Timestamp nowTs = Timestamp.from(Instant.now());
        StaffAuthAccount account = new StaffAuthAccount(
                userId,
                "user@homestay.com",
                "$2a$12$someStoredHashPlaceholder",
                "John",
                "Doe",
                "+12345",
                true,
                false,
                0,
                null,
                null,
                nowTs.toInstant(),
                null,
                null
        );

        when(identityLookup.findByEmail("user@homestay.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("SecretPass123", "$2a$12$someStoredHashPlaceholder")).thenReturn(true);

        // Verification query returns identical credentials
        when(authJdbcTemplate.queryForMap(contains("SELECT password_hash"), eq(userId)))
                .thenReturn(Map.of("password_hash", "$2a$12$someStoredHashPlaceholder", "password_changed_at", nowTs));

        // Database rejects login success (e.g. account locked or deactivated in DB)
        when(authJdbcTemplate.queryForObject(contains("fn_record_staff_login_success"), eq(Boolean.class), eq(userId), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate("user@homestay.com", "SecretPass123", "10.0.0.1", "Agent"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");
    }

    // =========================================================================
    // TASK 1: Concurrent Password Change Unit Verification
    // =========================================================================

    @Test
    @DisplayName("Task 1: Concurrent password change detected during row lock aborts authentication")
    void testConcurrentPasswordMutationAbortsLogin() {
        UUID userId = UUID.randomUUID();
        Instant initialTs = Instant.now().minusSeconds(100);
        StaffAuthAccount account = new StaffAuthAccount(
                userId,
                "user@homestay.com",
                "$2a$12$oldStoredHash",
                "John",
                "Doe",
                "+12345",
                true,
                false,
                0,
                null,
                null,
                initialTs,
                null,
                null
        );

        when(identityLookup.findByEmail("user@homestay.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("OldPassword123", "$2a$12$oldStoredHash")).thenReturn(true);

        // The authoritative row query under lock returns a NEW password hash (mutated concurrently)
        Timestamp newTs = Timestamp.from(Instant.now());
        when(authJdbcTemplate.queryForMap(contains("SELECT password_hash"), eq(userId)))
                .thenReturn(Map.of("password_hash", "$2a$12$newMutatedPasswordHash", "password_changed_at", newTs));

        assertThatThrownBy(() -> authService.authenticate("user@homestay.com", "OldPassword123", "10.0.0.1", "Agent"))
                .isInstanceOf(StaffAuthenticationException.class)
                .hasMessage("Invalid email or password.");

        // fn_record_staff_login_success must NEVER be called when credential mismatch is detected
        verify(authJdbcTemplate, never()).queryForObject(
                contains("fn_record_staff_login_success"),
                eq(Boolean.class),
                any(), any(), any()
        );
    }

    // =========================================================================
    // TASK 2: Dummy BCrypt Verification Unit Tests
    // =========================================================================

    @Test
    @DisplayName("Task 2: Dummy BCrypt hash is syntactically valid, work factor 12, and performs real comparison")
    void testDummyBcryptHashProperties() {
        String hash = StaffAuthenticationService.DUMMY_BCRYPT_HASH;

        // 1. Structure: $2a$ or $2b$, 2 digits cost, 22 chars salt, 31 chars checksum (total 60 chars)
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(60);
        assertThat(hash).matches("^\\$2[ab]\\$12\\$[A-Za-z0-9./]{53}$");

        // 2. Cost factor extract
        String costStr = hash.substring(4, 6);
        assertThat(costStr).isEqualTo("12");

        // 3. Real BCrypt verification execution
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder(12);
        boolean result = realEncoder.matches("arbitraryRandomCandidatePassword#999", hash);
        assertThat(result).isFalse();

        // 4. Verify it does not match common test passwords
        assertThat(realEncoder.matches("password", hash)).isFalse();
        assertThat(realEncoder.matches("admin", hash)).isFalse();
        assertThat(realEncoder.matches("ValidSecurePass#2026!", hash)).isFalse();
    }

    // =========================================================================
    // TASK 3: Database Failure Semantics (Not Masked as Invalid Credentials)
    // =========================================================================

    @Test
    @DisplayName("Task 3: Database connection failures propagate and are NOT masked as StaffAuthenticationException")
    void testDatabaseFailurePropagates() {
        when(identityLookup.findByEmail("user@homestay.com"))
                .thenThrow(new CannotGetJdbcConnectionException("PostgreSQL connection refused"));

        // Must propagate CannotGetJdbcConnectionException directly rather than masking as "Invalid email or password"
        assertThatThrownBy(() -> authService.authenticate("user@homestay.com", "Password#123", "10.0.0.1", "Agent"))
                .isInstanceOf(CannotGetJdbcConnectionException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("Task 3: Database query failure during login attempt propagates directly")
    void testDatabaseFailureDuringAttemptPropagates() {
        when(identityLookup.findByEmail("ghost@homestay.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(authJdbcTemplate.queryForObject(contains("fn_record_staff_login_attempt"), eq(Boolean.class), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("Database lock acquisition timeout"));

        assertThatThrownBy(() -> authService.authenticate("ghost@homestay.com", "SecretPass123", "10.0.0.1", "Agent"))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("lock acquisition timeout");
    }
}
