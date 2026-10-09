package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.service.GuestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guests")
@RequiredArgsConstructor
public class GuestController {

    private final GuestService guestService;

    @PostMapping
    public ResponseEntity<GuestResponse> createGuest(
            @Valid @RequestBody GuestCreateRequest request) {

        GuestResponse response = guestService.createGuest(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GuestResponse> getGuestById(
            @PathVariable UUID id) {

        GuestResponse response = guestService.getGuestById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<GuestResponse>> getAllGuests() {

        List<GuestResponse> response = guestService.getAllGuests();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<GuestResponse> updateGuest(
            @PathVariable UUID id,
            @Valid @RequestBody GuestUpdateRequest request) {

        GuestResponse response =
                guestService.updateGuest(id, request);

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateGuest(
            @PathVariable UUID id) {

        guestService.deactivateGuest(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateGuest(
            @PathVariable UUID id) {

        guestService.activateGuest(id);

        return ResponseEntity.noContent().build();
    }
}