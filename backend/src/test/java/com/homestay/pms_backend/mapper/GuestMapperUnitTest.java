package com.homestay.pms_backend.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.enums.IdentityDocumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuestMapper & GuestResponse Privacy Unit Tests")
class GuestMapperUnitTest {

    private GuestMapper guestMapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        guestMapper = new GuestMapper();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    @Test
    @DisplayName("Privacy: Masks identity number keeping only last 4 characters")
    void shouldMaskIdentityNumber() {
        assertThat(GuestMapper.maskIdNumber("P12345678")).isEqualTo("****5678");
        assertThat(GuestMapper.maskIdNumber("123456789012")).isEqualTo("****9012");
        assertThat(GuestMapper.maskIdNumber("ABCDE")).isEqualTo("****BCDE");
    }

    @Test
    @DisplayName("Privacy: Masks short identity numbers with 4 asterisks")
    void shouldMaskShortIdentityNumber() {
        assertThat(GuestMapper.maskIdNumber("1234")).isEqualTo("****");
        assertThat(GuestMapper.maskIdNumber("12")).isEqualTo("****");
        assertThat(GuestMapper.maskIdNumber("A")).isEqualTo("****");
    }

    @Test
    @DisplayName("Privacy: Returns null for null or blank identity numbers")
    void shouldReturnNullForBlankIdentityNumber() {
        assertThat(GuestMapper.maskIdNumber(null)).isNull();
        assertThat(GuestMapper.maskIdNumber("")).isNull();
        assertThat(GuestMapper.maskIdNumber("   ")).isNull();
    }

    @Test
    @DisplayName("Privacy: Serialization never exposes idDocumentImageKey or raw idNumber")
    void shouldNeverSerializeIdDocumentImageKeyOrRawIdNumber() throws Exception {
        Guest guest = new Guest();
        guest.setId(UUID.randomUUID());
        guest.setName("Privacy Guest");
        guest.setEmail("privacy@example.com");
        guest.setIdType(IdentityDocumentType.PASSPORT);
        guest.setIdNumber("P987654321");
        guest.setIdDocumentImageKey("private/keys/passport_123.jpg");
        guest.setActive(true);

        GuestResponse response = guestMapper.toResponse(guest);

        assertThat(response.getIdNumber()).isEqualTo("****4321");
        assertThat(response.getIdDocumentImageKey()).isNull();

        String json = objectMapper.writeValueAsString(response);

        // idDocumentImageKey is ignored and absent from JSON
        assertThat(json).doesNotContain("idDocumentImageKey");
        assertThat(json).doesNotContain("private/keys/passport_123.jpg");

        // Raw identity number is not present
        assertThat(json).doesNotContain("P987654321");
        assertThat(json).contains("****4321");
    }

    @Test
    @DisplayName("Partial Update: Masked identity number does not overwrite original stored identity number")
    void shouldPreserveOriginalIdNumberWhenUpdateContainsMaskedValue() {
        Guest guest = new Guest();
        guest.setName("Original Name");
        guest.setIdNumber("CONFIDENTIAL_ID_9999");

        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setName("Updated Name");
        request.setIdNumber("****9999"); // client sent back masked value

        guestMapper.updateEntity(guest, request);

        assertThat(guest.getName()).isEqualTo("Updated Name");
        assertThat(guest.getIdNumber()).isEqualTo("CONFIDENTIAL_ID_9999"); // Preserved!
    }

    @Test
    @DisplayName("Partial Update: Real new identity number updates stored identity number")
    void shouldUpdateIdNumberWhenRealValueProvided() {
        Guest guest = new Guest();
        guest.setName("Original Name");
        guest.setIdNumber("CONFIDENTIAL_ID_9999");

        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setIdNumber("NEW_REAL_ID_1234");

        guestMapper.updateEntity(guest, request);

        assertThat(guest.getIdNumber()).isEqualTo("NEW_REAL_ID_1234");
    }
}
