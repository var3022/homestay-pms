package com.homestay.pms_backend.security.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Step 3.3C.3: Staff Authentication State Validator Unit Tests")
class StaffAuthenticationStateValidatorUnitTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-13T12:00:00Z");

    private JdbcTemplate authJdbcTemplate;
    private MutableClock mutableClock;
    private StaffAuthenticationStateValidator validator;
    private UUID sampleUserId;

    @BeforeEach
    void setUp() {
        authJdbcTemplate = mock(JdbcTemplate.class);
        mutableClock = new MutableClock(FIXED_NOW);
        validator = new StaffAuthenticationStateValidator(authJdbcTemplate, mutableClock);
        sampleUserId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    // =========================================================================
    // 1. Task 1: Lockout Policy Consistency
    // =========================================================================

    @Test
    @DisplayName("Active & Unlocked: Returns true when staff account is active, unlocked, and password unchanged")
    void shouldReturnTrueForActiveUnlockedAccount() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Active Lockout: Rejects token when staff is under active temporary lockout (lockout_until > now)")
    void shouldRejectActiveTimedLockout() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", true);
        row.put("lockout_until", Timestamp.from(FIXED_NOW.plusSeconds(300))); // lockout ends in 5 mins
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, FIXED_NOW))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Permanent Lockout: Rejects token when staff is locked indefinitely (is_locked=true, lockout_until=null)")
    void shouldRejectIndefinitelyLockedAccount() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", true);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, FIXED_NOW))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Expired Lockout: Accepts token when temporary lockout has elapsed without becoming a permanent lock")
    void shouldAcceptExpiredTimedLockout() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", true);
        row.put("lockout_until", Timestamp.from(FIXED_NOW.minusSeconds(10))); // lockout expired 10s ago
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Desynchronized Lockout: Rejects token if lockout_until > now even if is_locked flag is false")
    void shouldRejectDesynchronizedActiveLockout() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false); // desynchronized flag
        row.put("lockout_until", Timestamp.from(FIXED_NOW.plusSeconds(120))); // but active lockout in future
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Lockout Boundary: Lockout is active 1ms before boundary and expires exactly at boundary")
    void shouldHandleLockoutExactBoundaries() {
        Instant lockoutBoundary = Instant.parse("2026-11-13T12:05:00Z");
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", true);
        row.put("lockout_until", Timestamp.from(lockoutBoundary));
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        // 1. Immediately before lockout boundary -> REJECT
        mutableClock.setInstant(lockoutBoundary.minusMillis(1));
        assertThat(validator.isAccountStateValid(sampleUserId, FIXED_NOW)).isFalse();

        // 2. Exactly at lockout boundary -> ACCEPT (lockout duration has reached 0)
        mutableClock.setInstant(lockoutBoundary);
        assertThat(validator.isAccountStateValid(sampleUserId, FIXED_NOW)).isTrue();

        // 3. Immediately after lockout boundary -> ACCEPT (lockout expired)
        mutableClock.setInstant(lockoutBoundary.plusMillis(1));
        assertThat(validator.isAccountStateValid(sampleUserId, FIXED_NOW)).isTrue();
    }

    // =========================================================================
    // 2. Task 2: Same-Second Password Change Invalidation
    // =========================================================================

    @Test
    @DisplayName("Same-Second Invalidation: Rejects token when password changed in same second after issuance")
    void shouldRejectSameSecondPasswordChangeAfterIssuance() {
        Instant tokenIssuedAt = Instant.parse("2026-11-13T12:00:00.200Z");
        Instant passwordChangedAt = Instant.parse("2026-11-13T12:00:00.800Z"); // 600ms later, same second

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(passwordChangedAt));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, tokenIssuedAt);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, tokenIssuedAt))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Same-Second Non-Invalidation: Accepts legitimate token issued in same second after password change")
    void shouldAcceptSameSecondIssuanceAfterPasswordChange() {
        Instant passwordChangedAt = Instant.parse("2026-11-13T12:00:00.100Z");
        Instant tokenIssuedAt = Instant.parse("2026-11-13T12:00:00.500Z"); // 400ms after password change

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(passwordChangedAt));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, tokenIssuedAt);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Ambiguous Boundary: Fails closed when password_changed_at is exactly equal to tokenIssuedAt")
    void shouldFailClosedWhenPasswordChangedAtEqualsTokenIssuedAt() {
        Instant exactInstant = Instant.parse("2026-11-13T12:00:00.500Z");

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(exactInstant));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, exactInstant);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, exactInstant))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Sub-Second Non-Invalidation: Accepts token when password changed 1ms before token issuance")
    void shouldAcceptWhenPasswordChangedOneMillisecondBeforeIssuance() {
        Instant tokenIssuedAt = Instant.parse("2026-11-13T12:00:00.500Z");
        Instant passwordChangedAt = tokenIssuedAt.minusMillis(1); // 1ms before token issuance

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(passwordChangedAt));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, tokenIssuedAt);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Sub-Second Invalidation: Rejects token when password changed immediately after issuance (next millisecond)")
    void shouldRejectPasswordChangeImmediatelyAfterIssuance() {
        Instant tokenIssuedAt = Instant.parse("2026-11-13T12:00:00.999Z");
        Instant passwordChangedAt = Instant.parse("2026-11-13T12:00:01.000Z"); // 1ms later across second boundary

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(passwordChangedAt));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, tokenIssuedAt);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Temporal Invalidation: Rejects token when password changed between issuance and validation")
    void shouldRejectPasswordChangeBetweenIssuanceAndValidation() {
        Instant tokenIssuedAt = Instant.parse("2026-11-13T11:55:00Z");
        Instant passwordChangedAt = Instant.parse("2026-11-13T11:58:00Z"); // 3 mins after issuance

        // Validation happens at 12:00:00Z
        mutableClock.setInstant(FIXED_NOW);

        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(passwordChangedAt));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, tokenIssuedAt);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Ambiguous Values: Fails closed when password_changed_at is null or missing")
    void shouldFailClosedWhenPasswordChangedAtMissing() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", true);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", null); // Missing / null timestamp

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, FIXED_NOW))
                .isInstanceOf(JwtValidationException.class);
    }

    // =========================================================================
    // 3. User Existence & Input Hygiene
    // =========================================================================

    @Test
    @DisplayName("Missing Account: Returns false and throws JwtValidationException when staff user does not exist")
    void shouldRejectWhenUserDoesNotExist() {
        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId)))
                .thenThrow(new EmptyResultDataAccessException(1));

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, FIXED_NOW))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Staff account is deactivated, locked, revoked, or no longer exists");
    }

    @Test
    @DisplayName("Deactivated: Returns false and throws when staff is_active is false")
    void shouldRejectDeactivatedAccount() {
        Map<String, Object> row = new HashMap<>();
        row.put("is_active", false);
        row.put("is_locked", false);
        row.put("lockout_until", null);
        row.put("password_changed_at", Timestamp.from(FIXED_NOW.minusSeconds(3600)));

        when(authJdbcTemplate.queryForMap(any(String.class), eq(sampleUserId))).thenReturn(row);

        boolean valid = validator.isAccountStateValid(sampleUserId, FIXED_NOW);
        assertThat(valid).isFalse();

        assertThatThrownBy(() -> validator.validateAccountState(sampleUserId, FIXED_NOW))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("Null Safety: Safely handles null staff user ID or token issuance instant")
    void shouldReturnFalseForNullInputs() {
        assertThat(validator.isAccountStateValid(null, FIXED_NOW)).isFalse();
        assertThat(validator.isAccountStateValid(sampleUserId, null)).isFalse();
    }

    // =========================================================================
    // Helper MutableClock for Deterministic Temporal Testing
    // =========================================================================

    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zoneId = ZoneId.of("UTC");

        MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
