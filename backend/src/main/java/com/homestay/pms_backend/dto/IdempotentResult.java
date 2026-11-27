package com.homestay.pms_backend.dto;

public record IdempotentResult<T>(
        T data,
        boolean replayed,
        String idempotencyKey
) {
}
