package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.BookingSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingSourceRepository extends JpaRepository<BookingSource, UUID> {

    Optional<BookingSource> findByCode(String code);
}