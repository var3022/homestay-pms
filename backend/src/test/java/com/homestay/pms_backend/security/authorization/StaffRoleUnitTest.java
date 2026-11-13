package com.homestay.pms_backend.security.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StaffRole Unit Tests")
class StaffRoleUnitTest {

    @Test
    @DisplayName("SUPER_ADMIN must be allowed globally and forbidden property-scoped")
    void superAdminRoleConstraints() {
        StaffRole role = StaffRole.SUPER_ADMIN;
        assertThat(role.getCode()).isEqualTo("SUPER_ADMIN");
        assertThat(role.isGlobalAllowed()).isTrue();
        assertThat(role.isPropertyAllowed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROPERTY_ADMIN", "RECEPTIONIST", "ACCOUNTANT", "VIEWER"})
    @DisplayName("Operational roles must be allowed property-scoped and forbidden globally")
    void operationalRoleConstraints(String roleCode) {
        StaffRole role = StaffRole.fromCode(roleCode);
        assertThat(role.getCode()).isEqualTo(roleCode);
        assertThat(role.isGlobalAllowed()).isFalse();
        assertThat(role.isPropertyAllowed()).isTrue();
    }

    @Test
    @DisplayName("fromCode parses case-insensitively and trims whitespace")
    void fromCodeParsesValidCodes() {
        assertThat(StaffRole.fromCode("super_admin")).isEqualTo(StaffRole.SUPER_ADMIN);
        assertThat(StaffRole.fromCode("  PROPERTY_ADMIN  ")).isEqualTo(StaffRole.PROPERTY_ADMIN);
        assertThat(StaffRole.fromCode("Receptionist")).isEqualTo(StaffRole.RECEPTIONIST);
        assertThat(StaffRole.fromCode("accountant")).isEqualTo(StaffRole.ACCOUNTANT);
        assertThat(StaffRole.fromCode("VIEWER")).isEqualTo(StaffRole.VIEWER);
    }

    @Test
    @DisplayName("fromCode rejects unknown role codes with IllegalArgumentException")
    void fromCodeRejectsUnknownRole() {
        assertThatThrownBy(() -> StaffRole.fromCode("ADMINISTRATOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown staff role code: ADMINISTRATOR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("fromCode rejects empty or blank strings")
    void fromCodeRejectsBlank(String blankCode) {
        assertThatThrownBy(() -> StaffRole.fromCode(blankCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Staff role code must not be null or blank");
    }

    @Test
    @DisplayName("fromCode rejects null")
    void fromCodeRejectsNull() {
        assertThatThrownBy(() -> StaffRole.fromCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Staff role code must not be null or blank");
    }

    @Test
    @DisplayName("findByCode returns matching role for valid inputs and empty for invalid")
    void findByCodeSafeLookup() {
        assertThat(StaffRole.findByCode("SUPER_ADMIN")).contains(StaffRole.SUPER_ADMIN);
        assertThat(StaffRole.findByCode("super_admin")).contains(StaffRole.SUPER_ADMIN);
        assertThat(StaffRole.findByCode("UNKNOWN")).isEmpty();
        assertThat(StaffRole.findByCode("")).isEmpty();
        assertThat(StaffRole.findByCode(null)).isEmpty();
    }
}
