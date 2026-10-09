package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.Guest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {

    List<Guest> findByNameIgnoreCase(String name);

    List<Guest> findByPhone(String phone);

    List<Guest> findByEmailIgnoreCase(String email);
}