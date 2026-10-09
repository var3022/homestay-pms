package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.ReservationRoomAddRequest;
import com.homestay.pms_backend.dto.response.ReservationRoomResponse;
import com.homestay.pms_backend.service.ReservationRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservation-rooms")
@RequiredArgsConstructor
public class ReservationRoomController {

    private final ReservationRoomService reservationRoomService;

    @PostMapping
    public ResponseEntity<ReservationRoomResponse> addReservationRoom(
            @Valid @RequestBody ReservationRoomAddRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(reservationRoomService.addReservationRoom(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationRoomResponse> getReservationRoomById(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                reservationRoomService.getReservationRoomById(id)
        );
    }

    @GetMapping
    public ResponseEntity<List<ReservationRoomResponse>> getReservationRooms(
            @RequestParam UUID reservationId) {

        return ResponseEntity.ok(
                reservationRoomService
                        .getReservationRoomsByReservationId(reservationId)
        );
    }
}