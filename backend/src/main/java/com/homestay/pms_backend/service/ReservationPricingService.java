package com.homestay.pms_backend.service;

import com.homestay.pms_backend.exception.BusinessValidationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class ReservationPricingService {

    public static final int CURRENCY_SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    private static final BigDecimal MAX_SUBTOTAL = new BigDecimal("9999999999.99");

    /**
     * Calculates the number of nights between check-in and check-out dates.
     * Validates that both dates are provided and that checkOutDate is strictly after checkInDate.
     */
    public long calculateNights(LocalDate checkInDate, LocalDate checkOutDate) {
        if (checkInDate == null || checkOutDate == null) {
            throw new BusinessValidationException("Check-in and check-out dates must not be null");
        }
        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        if (nights < 1) {
            throw new BusinessValidationException("Stay duration must be at least 1 night");
        }
        return nights;
    }

    /**
     * Calculates the current active accommodation subtotal:
     * subtotal = activeQuantity * nights * unitPrice
     *
     * where activeQuantity = quantity - cancelledQuantity.
     * If activeQuantity == 0, subtotal is 0.00.
     */
    public BigDecimal calculateAccommodationSubtotal(
            int quantity,
            int cancelledQuantity,
            BigDecimal unitPrice,
            LocalDate checkInDate,
            LocalDate checkOutDate) {

        if (quantity < 1) {
            throw new BusinessValidationException("Room quantity must be at least 1");
        }
        if (cancelledQuantity < 0) {
            throw new BusinessValidationException("Cancelled quantity cannot be negative");
        }
        if (cancelledQuantity > quantity) {
            throw new BusinessValidationException("Cancelled quantity cannot exceed total quantity");
        }
        if (unitPrice == null) {
            throw new BusinessValidationException("Unit price must not be null");
        }
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessValidationException("Unit price cannot be negative");
        }

        int activeQuantity = quantity - cancelledQuantity;
        if (activeQuantity == 0) {
            return BigDecimal.ZERO.setScale(CURRENCY_SCALE, ROUNDING_MODE);
        }

        long nights = calculateNights(checkInDate, checkOutDate);

        BigDecimal normalizedUnitPrice = unitPrice.setScale(CURRENCY_SCALE, ROUNDING_MODE);
        BigDecimal nightsMultiplier = BigDecimal.valueOf(nights);
        BigDecimal quantityMultiplier = BigDecimal.valueOf(activeQuantity);

        BigDecimal subtotal = quantityMultiplier
                .multiply(nightsMultiplier)
                .multiply(normalizedUnitPrice)
                .setScale(CURRENCY_SCALE, ROUNDING_MODE);

        if (subtotal.compareTo(MAX_SUBTOTAL) > 0) {
            throw new BusinessValidationException("Calculated subtotal exceeds maximum allowable precision");
        }

        return subtotal;
    }
}
