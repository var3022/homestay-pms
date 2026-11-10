package com.homestay.pms_backend.migration;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@IsolatedDatabaseTest
@DisplayName("Database Upgrade Compatibility & Ownership Verification Tests")
class DatabaseUpgradeCompatibilityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify that all public schema tables are owned by migration owner pms_admin, not runtime pms_app")
    void shouldVerifyTableOwnershipBelongsToPmsAdmin() {
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public'"
        );

        assertThat(tables).isNotEmpty();

        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("tablename");
            String owner = (String) table.get("tableowner");

            assertThat(owner)
                    .as("Table %s must be owned by pms_admin to allow DDL migrations", tableName)
                    .isEqualTo("pms_admin");
            assertThat(owner)
                    .as("Table %s must never be owned by runtime role pms_app", tableName)
                    .isNotEqualTo("pms_app");
        }
    }

    @Test
    @DisplayName("Verify that V7 migration prerequisite constraint uq_reservations_property_id exists on reservations")
    void shouldVerifyReservationsUniqueConstraintExists() {
        Integer constraintCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'reservations'::regclass AND conname = 'uq_reservations_property_id'",
                Integer.class
        );

        assertThat(constraintCount).isNotNull().isEqualTo(1);
    }

    @Test
    @DisplayName("Verify runtime role pms_app is strictly denied access to flyway_schema_history metadata table")
    void shouldRejectAccessToFlywaySchemaHistoryUnderRuntimeRole() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.execute("SELECT * FROM flyway_schema_history"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table flyway_schema_history");
    }

    @Test
    @DisplayName("Verify that all 6 foundational finance tables exist and are owned by pms_admin")
    void shouldVerifyFinanceTablesExistAndOwnedByPmsAdmin() {
        List<String> financeTables = List.of(
                "property_financial_settings",
                "finance_accounts",
                "finance_accounting_periods",
                "finance_journal_entries",
                "finance_journal_lines",
                "finance_audit_events"
        );

        for (String tableName : financeTables) {
            String owner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    String.class,
                    tableName
            );

            assertThat(owner)
                    .as("Finance table %s must exist and be owned by pms_admin", tableName)
                    .isEqualTo("pms_admin");
        }
    }
}
