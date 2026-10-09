package com.homestay.pms_backend.mapper;

import com.homestay.pms_backend.dto.request.GuestCreateRequest;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.dto.response.GuestResponse;
import com.homestay.pms_backend.entity.Guest;
import org.springframework.stereotype.Component;

@Component
public class GuestMapper {

    public Guest toEntity(GuestCreateRequest request) {
        Guest guest = new Guest();

        guest.setName(request.getName());
        guest.setPhone(request.getPhone());
        guest.setEmail(request.getEmail());
        guest.setAddress(request.getAddress());
        guest.setCity(request.getCity());
        guest.setState(request.getState());
        guest.setCountry(request.getCountry());
        guest.setPostalCode(request.getPostalCode());
        guest.setIdType(request.getIdType());
        guest.setIdNumber(request.getIdNumber());
        guest.setIdDocumentImageKey(request.getIdDocumentImageKey());

        return guest;
    }

    public void updateEntity(Guest guest, GuestUpdateRequest request) {
        if (request.getName() != null) {
            guest.setName(request.getName());
        }

        if (request.getPhone() != null) {
            guest.setPhone(request.getPhone());
        }

        if (request.getEmail() != null) {
            guest.setEmail(request.getEmail());
        }

        if (request.getAddress() != null) {
            guest.setAddress(request.getAddress());
        }

        if (request.getCity() != null) {
            guest.setCity(request.getCity());
        }

        if (request.getState() != null) {
            guest.setState(request.getState());
        }

        if (request.getCountry() != null) {
            guest.setCountry(request.getCountry());
        }

        if (request.getPostalCode() != null) {
            guest.setPostalCode(request.getPostalCode());
        }

        if (request.getIdType() != null) {
            guest.setIdType(request.getIdType());
        }

        if (request.getIdNumber() != null) {
            guest.setIdNumber(request.getIdNumber());
        }

        if (request.getIdDocumentImageKey() != null) {
            guest.setIdDocumentImageKey(request.getIdDocumentImageKey());
        }
    }

    public GuestResponse toResponse(Guest guest) {
        GuestResponse response = new GuestResponse();

        response.setId(guest.getId());
        response.setName(guest.getName());
        response.setPhone(guest.getPhone());
        response.setEmail(guest.getEmail());
        response.setAddress(guest.getAddress());
        response.setCity(guest.getCity());
        response.setState(guest.getState());
        response.setCountry(guest.getCountry());
        response.setPostalCode(guest.getPostalCode());
        response.setIdType(guest.getIdType());
        response.setIdNumber(guest.getIdNumber());
        response.setIdDocumentImageKey(guest.getIdDocumentImageKey());
        response.setActive(guest.isActive());
        response.setCreatedAt(guest.getCreatedAt());
        response.setUpdatedAt(guest.getUpdatedAt());

        return response;
    }
}