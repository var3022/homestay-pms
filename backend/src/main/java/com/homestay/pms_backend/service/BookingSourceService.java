package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.BookingSourceUpdateRequest;
import com.homestay.pms_backend.dto.response.BookingSourceResponse;

import java.util.List;
import java.util.UUID;

public interface BookingSourceService {

    BookingSourceResponse getBookingSourceById(UUID id);

    List<BookingSourceResponse> getAllBookingSources();

    BookingSourceResponse updateBookingSource(
            UUID id,
            BookingSourceUpdateRequest request);

    void deactivateBookingSource(UUID id);

    void activateBookingSource(UUID id);
}