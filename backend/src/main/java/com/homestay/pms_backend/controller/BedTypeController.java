package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.dto.request.BedTypeCreateRequest;
import com.homestay.pms_backend.dto.request.BedTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.BedTypeResponse;
import com.homestay.pms_backend.service.BedTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bed-types")
@RequiredArgsConstructor
public class BedTypeController {

    private final BedTypeService bedTypeService;

    @PostMapping
    public ResponseEntity<BedTypeResponse> createBedType(
            @Valid @RequestBody BedTypeCreateRequest request) {

        BedTypeResponse response =
                bedTypeService.createBedType(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BedTypeResponse> getBedTypeById(
            @PathVariable UUID id) {

        BedTypeResponse response =
                bedTypeService.getBedTypeById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<BedTypeResponse>> getAllBedTypes() {

        List<BedTypeResponse> response =
                bedTypeService.getAllBedTypes();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BedTypeResponse> updateBedType(
            @PathVariable UUID id,
            @Valid @RequestBody BedTypeUpdateRequest request) {

        BedTypeResponse response =
                bedTypeService.updateBedType(id, request);

        return ResponseEntity.ok(response);
    }
}