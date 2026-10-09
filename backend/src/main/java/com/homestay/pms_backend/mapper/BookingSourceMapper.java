package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.BookingSourceUpdateRequest;
import com.homestay.pms_backend.dto.response.BookingSourceResponse;
import com.homestay.pms_backend.entity.BookingSource;
import org.springframework.stereotype.Component;

@Component
public class BookingSourceMapper {

    public void updateEntity(
            BookingSource bookingSource,
            BookingSourceUpdateRequest request) {

        if (request.getName() != null) {
            bookingSource.setName(request.getName());
        }

        if (request.getActive() != null) {
            bookingSource.setActive(request.getActive());
        }
    }

    public BookingSourceResponse toResponse(BookingSource bookingSource) {

        BookingSourceResponse response = new BookingSourceResponse();

        response.setId(bookingSource.getId());
        response.setCode(bookingSource.getCode());
        response.setName(bookingSource.getName());
        response.setActive(bookingSource.isActive());
        response.setCreatedAt(bookingSource.getCreatedAt());

        return response;
    }
}