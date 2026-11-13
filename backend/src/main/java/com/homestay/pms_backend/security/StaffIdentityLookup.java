package com.homestay.pms_backend.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff identity lookup component operating strictly over the dedicated pms_auth DataSource.
 * Loads staff authentication state, lockout metadata, and BCrypt hashes without using pms_app.
 */
@Component
@ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
public class StaffIdentityLookup {

    private final JdbcTemplate authJdbcTemplate;

    private static final String SELECT_BY_EMAIL = """
            SELECT id, email, password_hash, first_name, last_name, phone,
                   is_active, is_locked, failed_login_attempts, lockout_until,
                   last_login_at, password_changed_at, created_at, updated_at
            FROM staff_users
            WHERE email = ?
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, email, password_hash, first_name, last_name, phone,
                   is_active, is_locked, failed_login_attempts, lockout_until,
                   last_login_at, password_changed_at, created_at, updated_at
            FROM staff_users
            WHERE id = ?
            """;

    private static final RowMapper<StaffAuthAccount> ROW_MAPPER = (rs, rowNum) -> new StaffAuthAccount(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("phone"),
            rs.getBoolean("is_active"),
            rs.getBoolean("is_locked"),
            rs.getInt("failed_login_attempts"),
            toInstant(rs.getTimestamp("lockout_until")),
            toInstant(rs.getTimestamp("last_login_at")),
            toInstant(rs.getTimestamp("password_changed_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    public StaffIdentityLookup(@Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate) {
        this.authJdbcTemplate = authJdbcTemplate;
    }

    /**
     * Normalizes email addresses consistently with the database schema constraint:
     * lower(trim(email)).
     */
    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks up staff identity by email using normalized matching and parameterized SQL.
     */
    public Optional<StaffAuthAccount> findByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isBlank() || normalized.indexOf('@') <= 0 || normalized.indexOf('@') == normalized.length() - 1) {
            return Optional.empty();
        }

        try {
            StaffAuthAccount account = authJdbcTemplate.queryForObject(
                    SELECT_BY_EMAIL,
                    ROW_MAPPER,
                    normalized
            );
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Looks up staff identity by UUID using parameterized SQL.
     */
    public Optional<StaffAuthAccount> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }

        try {
            StaffAuthAccount account = authJdbcTemplate.queryForObject(
                    SELECT_BY_ID,
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
