package com.homestay.pms_backend.exception;

public class IdempotencyExpiredException extends RuntimeException {
    public IdempotencyExpiredException(String message) {
        super(message);
    }
}
