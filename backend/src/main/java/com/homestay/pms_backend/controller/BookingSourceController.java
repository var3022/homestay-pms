package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.BookingSourceUpdateRequest;
import com.homestay.pms_backend.dto.response.BookingSourceResponse;
import com.homestay.pms_backend.service.BookingSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/booking-sources")
@RequiredArgsConstructor
public class BookingSourceController {

    private final BookingSourceService bookingSourceService;

    @GetMapping("/{id}")
    public ResponseEntity<BookingSourceResponse> getBookingSourceById(
            @PathVariable UUID id) {

        BookingSourceResponse response =
                bookingSourceService.getBookingSourceById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<BookingSourceResponse>> getAllBookingSources() {

        List<BookingSourceResponse> response =
                bookingSourceService.getAllBookingSources();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BookingSourceResponse> updateBookingSource(
            @PathVariable UUID id,
            @Valid @RequestBody BookingSourceUpdateRequest request) {

        BookingSourceResponse response =
                bookingSourceService.updateBookingSource(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateBookingSource(
            @PathVariable UUID id) {

        bookingSourceService.deactivateBookingSource(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateBookingSource(
            @PathVariable UUID id) {

        bookingSourceService.activateBookingSource(id);

        return ResponseEntity.noContent().build();
    }
}