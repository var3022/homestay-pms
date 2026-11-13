package com.homestay.pms_backend.security.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StaffOperation Unit Tests")
class StaffOperationUnitTest {

    @Test
    @DisplayName("PROPERTY_READ authorizes all five staff roles")
    void propertyReadAuthorizations() {
        StaffOperation op = StaffOperation.PROPERTY_READ;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isTrue();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isTrue();
    }

    @Test
    @DisplayName("PROPERTY_MANAGE authorizes only SUPER_ADMIN and PROPERTY_ADMIN")
    void propertyManageAuthorizations() {
        StaffOperation op = StaffOperation.PROPERTY_MANAGE;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isFalse();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("ROOM_READ authorizes all five staff roles")
    void roomReadAuthorizations() {
        StaffOperation op = StaffOperation.ROOM_READ;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isTrue();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isTrue();
    }

    @Test
    @DisplayName("ROOM_MANAGE authorizes only SUPER_ADMIN and PROPERTY_ADMIN")
    void roomManageAuthorizations() {
        StaffOperation op = StaffOperation.ROOM_MANAGE;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isFalse();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("ROOM_STATUS_UPDATE authorizes SUPER_ADMIN, PROPERTY_ADMIN, and RECEPTIONIST")
    void roomStatusUpdateAuthorizations() {
        StaffOperation op = StaffOperation.ROOM_STATUS_UPDATE;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("RESERVATION_READ authorizes SUPER_ADMIN, PROPERTY_ADMIN, RECEPTIONIST, and ACCOUNTANT; strictly denies VIEWER")
    void reservationReadAuthorizations() {
        StaffOperation op = StaffOperation.RESERVATION_READ;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isTrue();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("RESERVATION_MANAGE authorizes SUPER_ADMIN, PROPERTY_ADMIN, and RECEPTIONIST")
    void reservationManageAuthorizations() {
        StaffOperation op = StaffOperation.RESERVATION_MANAGE;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("GUEST_READ authorizes SUPER_ADMIN, PROPERTY_ADMIN, and RECEPTIONIST; strictly denies VIEWER and ACCOUNTANT")
    void guestReadAuthorizations() {
        StaffOperation op = StaffOperation.GUEST_READ;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
    }

    @Test
    @DisplayName("GUEST_MANAGE authorizes SUPER_ADMIN, PROPERTY_ADMIN, and RECEPTIONIST")
    void guestManageAuthorizations() {
        StaffOperation op = StaffOperation.GUEST_MANAGE;
        assertThat(op.isAuthorized(StaffRole.SUPER_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.PROPERTY_ADMIN)).isTrue();
        assertThat(op.isAuthorized(StaffRole.RECEPTIONIST)).isTrue();
        assertThat(op.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(op.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("Strict Constraint: VIEWER must never gain write permissions across any domain operation")
    void viewerNeverGainsWritePermissions() {
        for (StaffOperation op : StaffOperation.values()) {
            if (op.name().contains("MANAGE") || op.name().contains("UPDATE")) {
                assertThat(op.isAuthorized(StaffRole.VIEWER))
                        .as("VIEWER must not be authorized for %s", op.name())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Strict Constraint: ACCOUNTANT must never gain room, reservation, or guest mutations")
    void accountantNeverGainsMutations() {
        assertThat(StaffOperation.ROOM_MANAGE.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(StaffOperation.ROOM_STATUS_UPDATE.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(StaffOperation.RESERVATION_MANAGE.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(StaffOperation.GUEST_MANAGE.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
        assertThat(StaffOperation.GUEST_READ.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
    }

    @Test
    @DisplayName("Strict Constraint: RECEPTIONIST must never gain property or room administrative management")
    void receptionistNeverGainsAdministrativeManagement() {
        assertThat(StaffOperation.PROPERTY_MANAGE.isAuthorized(StaffRole.RECEPTIONIST)).isFalse();
        assertThat(StaffOperation.ROOM_MANAGE.isAuthorized(StaffRole.RECEPTIONIST)).isFalse();
    }

    @Test
    @DisplayName("Guest Privacy Constraint: VIEWER must never access GUEST_READ")
    void viewerNeverAccessesGuestRead() {
        assertThat(StaffOperation.GUEST_READ.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("Reservation Privacy Constraint: VIEWER must never access RESERVATION_READ")
    void viewerNeverAccessesReservationRead() {
        assertThat(StaffOperation.RESERVATION_READ.isAuthorized(StaffRole.VIEWER)).isFalse();
    }

    @Test
    @DisplayName("Guest Privacy Constraint: ACCOUNTANT must never access GUEST_READ")
    void accountantNeverAccessesGuestRead() {
        assertThat(StaffOperation.GUEST_READ.isAuthorized(StaffRole.ACCOUNTANT)).isFalse();
    }

    @Test
    @DisplayName("Null role is rejected safely with false")
    void nullRoleReturnsFalse() {
        for (StaffOperation op : StaffOperation.values()) {
            assertThat(op.isAuthorized(null)).isFalse();
        }
    }
}
