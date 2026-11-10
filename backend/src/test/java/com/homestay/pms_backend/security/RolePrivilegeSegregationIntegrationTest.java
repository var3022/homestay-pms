package com.homestay.pms_backend.security;

import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.enums.IdentityDocumentType;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedDatabaseTest
@DisplayName("Role & Privilege Segregation Integration Tests")
class RolePrivilegeSegregationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Test
    @DisplayName("Runtime database connection must execute as least-privilege role pms_app")
    void shouldConnectAsRuntimeRolePmsApp() {
        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);

        assertThat(currentUser).isEqualTo("pms_app");
        assertThat(currentDb).isEqualTo("v7_disposable_db");
    }

    @Test
    @DisplayName("Runtime role pms_app must retain full operational CRUD on PMS tables (e.g., properties, guests)")
    @Transactional
    void shouldAllowStandardJpaCrudOnOperationalTables() {
        // 1. Verify read on properties
        long propertyCount = propertyRepository.count();
        assertThat(propertyCount).isGreaterThanOrEqualTo(0);

        // 2. Perform INSERT on guests
        Guest guest = new Guest();
        guest.setName("Integration Test Guest");
        guest.setEmail("test.guest@example.com");
        guest.setPhone("+919876543210");
        guest.setIdType(IdentityDocumentType.PASSPORT);
        guest.setIdNumber("Z1234567");
        guest.setActive(true);

        Guest savedGuest = guestRepository.save(guest);
        assertThat(savedGuest.getId()).isNotNull();

        // 3. Perform SELECT
        Guest retrievedGuest = guestRepository.findById(savedGuest.getId()).orElse(null);
        assertThat(retrievedGuest).isNotNull();
        assertThat(retrievedGuest.getName()).isEqualTo("Integration Test Guest");

        // 4. Perform UPDATE
        retrievedGuest.setPhone("+919876543299");
        guestRepository.saveAndFlush(retrievedGuest);

        Guest updatedGuest = guestRepository.findById(savedGuest.getId()).orElse(null);
        assertThat(updatedGuest).isNotNull();
        assertThat(updatedGuest.getPhone()).isEqualTo("+919876543299");

        // 5. Perform DELETE
        guestRepository.delete(updatedGuest);
        guestRepository.flush();

        assertThat(guestRepository.findById(savedGuest.getId())).isEmpty();
    }

    @Test
    @DisplayName("Runtime role pms_app must be strictly rejected from performing schema DDL (CREATE TABLE)")
    void shouldRejectCreateTableUnderRuntimeRole() {
        assertThatThrownBy(() -> jdbcTemplate.execute("CREATE TABLE forbidden_test_table (id INT PRIMARY KEY)"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for schema public");
    }

    @Test
    @DisplayName("Runtime role pms_app must be strictly rejected from dropping operational tables")
    void shouldRejectDropTableUnderRuntimeRole() {
        assertThatThrownBy(() -> jdbcTemplate.execute("DROP TABLE properties"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("must be owner of table properties");
    }

    @Test
    @DisplayName("Runtime role pms_app must be strictly rejected from mutating property_financial_settings directly")
    void shouldRejectDirectMutationOnProtectedFinanceSettings() {
        UUID randomPropertyId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "INSERT INTO property_financial_settings (property_id, base_currency, accounting_method, fiscal_year_start_month, fiscal_year_start_day) " +
                "VALUES ('" + randomPropertyId + "', 'INR', 'ACCRUAL', 4, 1)"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table property_financial_settings");
    }

    @Test
    @DisplayName("Runtime role pms_app must be strictly rejected from direct DELETE on finance_journal_entries")
    void shouldRejectDirectDeletionFromJournalEntries() {
        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM finance_journal_entries"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table finance_journal_entries");
    }

    @Test
    @DisplayName("Stored procedure permissions: pms_app has operational rights but strictly NO administrative rights")
    void shouldEnforceProcedureExecutionPrivileges() {
        // Operational procedures granted to pms_app:
        Boolean canPost = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_post_journal_entry(uuid, character varying)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canReverse = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_reverse_journal_entry(uuid, date, uuid, character varying, text, character varying, date)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canDiscard = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_discard_draft_journal_entry(uuid, text, character varying)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(canPost).isTrue();
        assertThat(canReverse).isTrue();
        assertThat(canDiscard).isTrue();

        // Administrative procedures strictly REVOKED from pms_app:
        Boolean canClose = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_close_accounting_period(uuid, character varying)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canInit = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_initialize_property_finances(uuid, character varying, smallint, date)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canCutover = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_app', 'fn_approve_historical_cutover(uuid, uuid, character varying)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(canClose).isFalse();
        assertThat(canInit).isFalse();
        assertThat(canCutover).isFalse();
    }

    @Test
    @DisplayName("Direct invocation of administrative procedures by pms_app must fail with permission denied")
    void shouldRejectDirectExecutionOfAdministrativeProcedures() {
        UUID dummyId = UUID.randomUUID();

        // Attempt fn_close_accounting_period
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT fn_close_accounting_period('" + dummyId + "', 'unauthorized_pms_app_user')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_close_accounting_period");

        // Attempt fn_initialize_property_finances
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT fn_initialize_property_finances('" + dummyId + "', 'INR', 4::smallint, CURRENT_DATE)"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_initialize_property_finances");

        // Attempt fn_approve_historical_cutover
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT fn_approve_historical_cutover('" + dummyId + "', '" + UUID.randomUUID() + "', 'unauthorized_pms_app_user')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_approve_historical_cutover");
    }

    @Test
    @DisplayName("Verify pms_finance_admin least-privilege matrix: authorized for tax/account mutations, forbidden on direct period mutations")
    void shouldVerifyFinanceAdminPrivilegesOnFinancialSettingsAndAccounts() {
        // Tax configuration and operational settings: column-level UPDATE granted to pms_finance_admin
        Boolean canUpdateTax = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'property_financial_settings', 'is_tax_enabled', 'UPDATE')",
                Boolean.class
        );
        Boolean canUpdateTaxId = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'property_financial_settings', 'tax_id_number', 'UPDATE')",
                Boolean.class
        );
        Boolean canUpdateBaseCurrency = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'property_financial_settings', 'base_currency', 'UPDATE')",
                Boolean.class
        );

        assertThat(canUpdateTax).isTrue();
        assertThat(canUpdateTaxId).isTrue();
        assertThat(canUpdateBaseCurrency).isFalse();

        // Chart of accounts management: INSERT and controlled UPDATE granted to pms_finance_admin
        Boolean canInsertAccount = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'finance_accounts', 'INSERT')",
                Boolean.class
        );
        Boolean canUpdateAccountName = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'finance_accounts', 'account_name', 'UPDATE')",
                Boolean.class
        );
        Boolean canUpdateAccountCode = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'finance_accounts', 'account_code', 'UPDATE')",
                Boolean.class
        );

        assertThat(canInsertAccount).isTrue();
        assertThat(canUpdateAccountName).isTrue();
        assertThat(canUpdateAccountCode).isFalse();

        // Direct UPDATE on accounting periods revoked from pms_finance_admin (closure only through fn_close_accounting_period)
        Boolean canDirectUpdatePeriod = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('pms_finance_admin', 'finance_accounting_periods', 'UPDATE')",
                Boolean.class
        );
        assertThat(canDirectUpdatePeriod).isFalse();

        // Critical accounting lock protection: books_locked_until_date cannot be updated directly by pms_finance_admin or pms_app
        Boolean canFinanceAdminUpdateLockDate = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'property_financial_settings', 'books_locked_until_date', 'UPDATE')",
                Boolean.class
        );
        Boolean canAppUpdateLockDate = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_app', 'property_financial_settings', 'books_locked_until_date', 'UPDATE')",
                Boolean.class
        );
        assertThat(canFinanceAdminUpdateLockDate).isFalse();
        assertThat(canAppUpdateLockDate).isFalse();
    }

    @Test
    @DisplayName("Verify accounting lock date protection: direct UPDATE is blocked and procedures are SECURITY DEFINER owned by pms_admin")
    void shouldVerifyAccountingLockDateProtectionAndSecurityDefinerOwnership() {
        // 1. Negative test: verify pms_app cannot execute direct UPDATE on books_locked_until_date
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "UPDATE property_financial_settings SET books_locked_until_date = '2020-01-01'"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table property_financial_settings");

        // 2. Negative test: verify pms_finance_admin is strictly denied UPDATE on books_locked_until_date
        Boolean finAdminCanUpdateLockDate = jdbcTemplate.queryForObject(
                "SELECT has_column_privilege('pms_finance_admin', 'property_financial_settings', 'books_locked_until_date', 'UPDATE')",
                Boolean.class
        );
        assertThat(finAdminCanUpdateLockDate)
                .as("pms_finance_admin must not have column-level UPDATE on books_locked_until_date")
                .isFalse();

        // 3. Confirm authorized procedures are SECURITY DEFINER and owned by pms_admin
        Boolean isCloseSecDef = jdbcTemplate.queryForObject(
                "SELECT p.prosecdef FROM pg_proc p JOIN pg_roles r ON p.proowner = r.oid " +
                "WHERE p.proname = 'fn_close_accounting_period' AND r.rolname = 'pms_admin'",
                Boolean.class
        );
        Boolean isCutoverSecDef = jdbcTemplate.queryForObject(
                "SELECT p.prosecdef FROM pg_proc p JOIN pg_roles r ON p.proowner = r.oid " +
                "WHERE p.proname = 'fn_approve_historical_cutover' AND r.rolname = 'pms_admin'",
                Boolean.class
        );

        assertThat(isCloseSecDef).as("fn_close_accounting_period must be SECURITY DEFINER owned by pms_admin").isTrue();
        assertThat(isCutoverSecDef).as("fn_approve_historical_cutover must be SECURITY DEFINER owned by pms_admin").isTrue();
    }

    @Test
    @DisplayName("Verify pms_finance_admin has EXECUTE permissions on all administrative procedures")
    void shouldVerifyFinanceAdminHasExecutionRightsOnAdminProcedures() {
        Boolean canClose = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_close_accounting_period(uuid, character varying)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canInit = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_initialize_property_finances(uuid, character varying, smallint, date)', 'EXECUTE')",
                Boolean.class
        );
        Boolean canCutover = jdbcTemplate.queryForObject(
                "SELECT has_function_privilege('pms_finance_admin', 'fn_approve_historical_cutover(uuid, uuid, character varying)', 'EXECUTE')",
                Boolean.class
        );

        assertThat(canClose).isTrue();
        assertThat(canInit).isTrue();
        assertThat(canCutover).isTrue();
    }
}

