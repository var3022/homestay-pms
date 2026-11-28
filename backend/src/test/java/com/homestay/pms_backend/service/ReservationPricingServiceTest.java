package com.homestay.pms_backend.service;

import com.homestay.pms_backend.exception.BusinessValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationPricingServiceTest {

    private ReservationPricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingService = new ReservationPricingService();
    }

    @Test
    @DisplayName("Test A: One room, one night calculation")
    void calculateAccommodationSubtotal_oneRoomOneNight() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 2);
        BigDecimal unitPrice = new BigDecimal("3000.00");

        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOut);

        assertThat(subtotal).isEqualByComparingTo("3000.00");
        assertThat(subtotal.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Test B: One room, multiple nights calculation")
    void calculateAccommodationSubtotal_oneRoomMultipleNights() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 4); // 3 nights
        BigDecimal unitPrice = new BigDecimal("2500.00");

        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOut);

        // 1 room * 3 nights * 2500.00 = 7500.00
        assertThat(subtotal).isEqualByComparingTo("7500.00");
    }

    @Test
    @DisplayName("Test C: Multiple rooms, multiple nights calculation")
    void calculateAccommodationSubtotal_multipleRoomsMultipleNights() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 5); // 4 nights
        BigDecimal unitPrice = new BigDecimal("3500.00");

        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                2, 0, unitPrice, checkIn, checkOut);

        // 2 rooms * 4 nights * 3500.00 = 28000.00
        assertThat(subtotal).isEqualByComparingTo("28000.00");
    }

    @Test
    @DisplayName("Test E: Zero-priced complimentary stay")
    void calculateAccommodationSubtotal_zeroPricedComplimentaryStay() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 3); // 2 nights
        BigDecimal unitPrice = BigDecimal.ZERO;

        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOut);

        assertThat(subtotal).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Test F: Invalid negative prices throw BusinessValidationException")
    void calculateAccommodationSubtotal_rejectsNegativePrice() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 2);
        BigDecimal unitPrice = new BigDecimal("-100.00");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOut))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit price cannot be negative");
    }

    @Test
    @DisplayName("Test G: Invalid date range (checkOut <= checkIn or same-day) throws BusinessValidationException")
    void calculateAccommodationSubtotal_rejectsInvalidDateRange() {
        LocalDate checkIn = LocalDate.of(2026, 12, 2);
        LocalDate checkOutSameDay = LocalDate.of(2026, 12, 2);
        LocalDate checkOutBefore = LocalDate.of(2026, 12, 1);
        BigDecimal unitPrice = new BigDecimal("2000.00");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOutSameDay))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Stay duration must be at least 1 night");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOutBefore))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Stay duration must be at least 1 night");
    }

    @Test
    @DisplayName("Test H: Decimal precision and HALF_UP rounding")
    void calculateAccommodationSubtotal_decimalPrecisionAndRounding() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 2); // 1 night
        // 1234.565 -> rounds up to 1234.57
        BigDecimal unitPrice = new BigDecimal("1234.565");

        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                1, 0, unitPrice, checkIn, checkOut);

        assertThat(subtotal).isEqualByComparingTo("1234.57");
        assertThat(subtotal.scale()).isEqualTo(2);

        // 1234.564 -> rounds down to 1234.56
        BigDecimal unitPriceDown = new BigDecimal("1234.564");
        BigDecimal subtotalDown = pricingService.calculateAccommodationSubtotal(
                1, 0, unitPriceDown, checkIn, checkOut);
        assertThat(subtotalDown).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("Test I: Partially cancelled quantity calculates active subtotal only")
    void calculateAccommodationSubtotal_partiallyCancelledQuantity() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 3); // 2 nights
        BigDecimal unitPrice = new BigDecimal("2000.00");

        // 3 booked, 1 cancelled -> 2 active rooms
        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                3, 1, unitPrice, checkIn, checkOut);

        // 2 active * 2 nights * 2000.00 = 8000.00
        assertThat(subtotal).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("Test J: Fully cancelled room line results in 0.00 subtotal")
    void calculateAccommodationSubtotal_fullyCancelledRoomLine() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 3); // 2 nights
        BigDecimal unitPrice = new BigDecimal("2000.00");

        // 2 booked, 2 cancelled -> 0 active rooms
        BigDecimal subtotal = pricingService.calculateAccommodationSubtotal(
                2, 2, unitPrice, checkIn, checkOut);

        assertThat(subtotal).isEqualByComparingTo("0.00");
        assertThat(subtotal.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Rejects invalid quantity configurations")
    void calculateAccommodationSubtotal_rejectsInvalidQuantities() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 2);
        BigDecimal unitPrice = new BigDecimal("1000.00");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                0, 0, unitPrice, checkIn, checkOut))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Room quantity must be at least 1");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                2, -1, unitPrice, checkIn, checkOut))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cancelled quantity cannot be negative");

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                2, 3, unitPrice, checkIn, checkOut))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Cancelled quantity cannot exceed total quantity");
    }

    @Test
    @DisplayName("Rejects null unit price")
    void calculateAccommodationSubtotal_rejectsNullUnitPrice() {
        LocalDate checkIn = LocalDate.of(2026, 12, 1);
        LocalDate checkOut = LocalDate.of(2026, 12, 2);

        assertThatThrownBy(() -> pricingService.calculateAccommodationSubtotal(
                1, 0, null, checkIn, checkOut))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit price must not be null");
    }
}
