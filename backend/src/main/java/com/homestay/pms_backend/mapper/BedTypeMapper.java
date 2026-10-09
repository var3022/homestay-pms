package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.BedTypeCreateRequest;
import com.homestay.pms_backend.dto.request.BedTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.BedTypeResponse;
import com.homestay.pms_backend.entity.BedType;
import org.springframework.stereotype.Component;

@Component
public class BedTypeMapper {

    public BedType toEntity(BedTypeCreateRequest request) {

        BedType bedType = new BedType();

        bedType.setCode(request.getCode());
        bedType.setName(request.getName());

        return bedType;
    }

    public void updateEntity(
            BedType bedType,
            BedTypeUpdateRequest request) {

        if (request.getName() != null) {
            bedType.setName(request.getName());
        }
    }

    public BedTypeResponse toResponse(BedType bedType) {

        BedTypeResponse response = new BedTypeResponse();

        response.setId(bedType.getId());
        response.setCode(bedType.getCode());
        response.setName(bedType.getName());
        response.setCreatedAt(bedType.getCreatedAt());

        return response;
    }
}