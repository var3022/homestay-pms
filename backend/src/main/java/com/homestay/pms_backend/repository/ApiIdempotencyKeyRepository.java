package com.homestay.pms_backend.repository;

import com.homestay.pms_backend.entity.ApiIdempotencyKey;
import com.homestay.pms_backend.enums.IdempotencyOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiIdempotencyKeyRepository extends JpaRepository<ApiIdempotencyKey, UUID> {

    Optional<ApiIdempotencyKey> findByPropertyIdAndOperationAndPrincipalIdAndIdempotencyKey(
            UUID propertyId,
            IdempotencyOperation operation,
            UUID principalId,
            String idempotencyKey
    );
}
