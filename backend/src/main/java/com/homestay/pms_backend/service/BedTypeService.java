package com.homestay.pms_backend.service;

import com.homestay.pms_backend.dto.request.BedTypeCreateRequest;
import com.homestay.pms_backend.dto.request.BedTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.BedTypeResponse;

import java.util.List;
import java.util.UUID;

public interface BedTypeService {

    BedTypeResponse createBedType(BedTypeCreateRequest request);

    BedTypeResponse getBedTypeById(UUID id);

    List<BedTypeResponse> getAllBedTypes();

    BedTypeResponse updateBedType(
            UUID id,
            BedTypeUpdateRequest request
    );
}