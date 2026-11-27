package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationCreateRequest;
import com.homestay.pms_backend.dto.request.ReservationRoomCreateRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdempotencyFingerprintService {

    public String generateGuestFingerprint(GuestCreateRequest request) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, "propertyId", normalizeUuid(request.getPropertyId()));
        appendField(sb, "name", normalizeString(request.getName()));
        appendField(sb, "phone", normalizeString(request.getPhone()));
        appendField(sb, "email", normalizeEmail(request.getEmail()));
        appendField(sb, "address", normalizeString(request.getAddress()));
        appendField(sb, "city", normalizeString(request.getCity()));
        appendField(sb, "state", normalizeString(request.getState()));
        appendField(sb, "country", normalizeString(request.getCountry()));
        appendField(sb, "postalCode", normalizeString(request.getPostalCode()));
        appendField(sb, "idType", normalizeEnum(request.getIdType()));
        appendField(sb, "idNumber", normalizeString(request.getIdNumber()));
        appendField(sb, "idDocumentImageKey", normalizeString(request.getIdDocumentImageKey()));

        return sha256Hex(sb.toString());
    }

    public String generateReservationFingerprint(ReservationCreateRequest request) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, "propertyId", normalizeUuid(request.getPropertyId()));
        appendField(sb, "primaryGuestId", normalizeUuid(request.getPrimaryGuestId()));
        appendField(sb, "checkInDate", normalizeDate(request.getCheckInDate()));
        appendField(sb, "checkOutDate", normalizeDate(request.getCheckOutDate()));
        appendField(sb, "adults", normalizeInt(request.getAdults()));
        appendField(sb, "children", normalizeInt(request.getChildren()));
        appendField(sb, "infants", normalizeInt(request.getInfants()));
        appendField(sb, "bookingSourceId", normalizeUuid(request.getBookingSourceId()));
        appendField(sb, "externalBookingId", normalizeString(request.getExternalBookingId()));
        appendField(sb, "specialRequests", normalizeString(request.getSpecialRequests()));

        sb.append("rooms:");
        if (request.getReservationRooms() == null) {
            sb.append("-1;");
        } else {
            List<ReservationRoomCreateRequest> sorted = request.getReservationRooms().stream()
                    .sorted(Comparator.comparing(
                            (ReservationRoomCreateRequest r) -> r.getRoomTypeId() != null ? r.getRoomTypeId().toString() : ""
                    ).thenComparingInt(ReservationRoomCreateRequest::getQuantity)
                     .thenComparing((ReservationRoomCreateRequest r) -> normalizeDecimal(r.getUnitPrice()),
                             Comparator.nullsFirst(String::compareTo)))
                    .toList();

            StringBuilder roomsSb = new StringBuilder();
            for (ReservationRoomCreateRequest room : sorted) {
                roomsSb.append("[");
                appendField(roomsSb, "roomTypeId", normalizeUuid(room.getRoomTypeId()));
                appendField(roomsSb, "quantity", normalizeInt(room.getQuantity()));
                appendField(roomsSb, "unitPrice", normalizeDecimal(room.getUnitPrice()));
                roomsSb.append("]");
            }
            String roomsStr = roomsSb.toString();
            sb.append(roomsStr.length()).append(":").append(roomsStr).append(";");
        }

        return sha256Hex(sb.toString());
    }

    private void appendField(StringBuilder sb, String fieldName, String value) {
        sb.append(fieldName).append(":");
        if (value == null) {
            sb.append("-1;");
        } else {
            sb.append(value.length()).append(":").append(value).append(";");
        }
    }

    private String normalizeString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeEmail(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeUuid(UUID uuid) {
        return uuid != null ? uuid.toString().toLowerCase(Locale.ROOT) : null;
    }

    private String normalizeDate(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private String normalizeInt(int value) {
        return Integer.toString(value);
    }

    private String normalizeEnum(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    private String normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
