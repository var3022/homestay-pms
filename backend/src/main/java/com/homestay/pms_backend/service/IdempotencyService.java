package com.homestay.pms_backend.service;

import com.homestay.pms_backend.enums.IdempotencyOperation;

import java.util.UUID;

public interface IdempotencyService {

    void validateKeyHeader(String idempotencyKey);

    UUID resolvePrincipalId();

    ClaimOutcome claimKey(UUID propertyId, IdempotencyOperation operation, UUID principalId, String idempotencyKey, String fingerprint);

    void completeClaim(UUID claimId, UUID resourceId, int responseStatus);
}
