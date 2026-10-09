package com.homestay.pms_backend.serviceimpl;

import com.homestay.pms_backend.dto.request.BedTypeCreateRequest;
import com.homestay.pms_backend.dto.request.BedTypeUpdateRequest;
import com.homestay.pms_backend.dto.response.BedTypeResponse;
import com.homestay.pms_backend.entity.BedType;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.mapper.BedTypeMapper;
import com.homestay.pms_backend.repository.BedTypeRepository;
import com.homestay.pms_backend.service.BedTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class BedTypeServiceImpl implements BedTypeService {

    private final BedTypeRepository bedTypeRepository;
    private final BedTypeMapper bedTypeMapper;

    @Override
    public BedTypeResponse createBedType(
            BedTypeCreateRequest request) {

        validateCodeDoesNotExist(request.getCode());

        BedType bedType = bedTypeMapper.toEntity(request);

        BedType savedBedType =
                bedTypeRepository.saveAndFlush(bedType);

        return bedTypeMapper.toResponse(savedBedType);
    }

    @Override
    @Transactional(readOnly = true)
    public BedTypeResponse getBedTypeById(UUID id) {

        BedType bedType = bedTypeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Bed type not found with id: " + id
                        )
                );

        return bedTypeMapper.toResponse(bedType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BedTypeResponse> getAllBedTypes() {

        return bedTypeRepository.findAll()
                .stream()
                .map(bedTypeMapper::toResponse)
                .toList();
    }

    @Override
    public BedTypeResponse updateBedType(
            UUID id,
            BedTypeUpdateRequest request) {

        BedType bedType = bedTypeRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Bed type not found with id: " + id
                        )
                );

        bedTypeMapper.updateEntity(
                bedType,
                request
        );

        BedType updatedBedType =
                bedTypeRepository.saveAndFlush(bedType);

        return bedTypeMapper.toResponse(updatedBedType);
    }

    private void validateCodeDoesNotExist(String code) {

        if (bedTypeRepository.existsByCode(code)) {

            throw new DuplicateResourceException(
                    "Bed type with code '"
                            + code
                            + "' already exists"
            );
        }
    }
}