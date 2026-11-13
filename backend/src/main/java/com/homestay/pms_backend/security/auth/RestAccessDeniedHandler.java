package com.homestay.pms_backend.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * REST Access Denied Handler returning standardized JSON 403 Forbidden responses.
 *
 * Ensures:
 * 1. Safe error message ("Access is denied.").
 * 2. Cache-Control: no-store and Pragma: no-cache are set.
 * 3. Consistent with GlobalExceptionHandler.ErrorResponse schema.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);
    private static final String DEFAULT_FORBIDDEN_MESSAGE = "Access is denied.";

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        log.warn("Access denied for request to [{}]: {}", request.getRequestURI(), accessDeniedException.getMessage());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");

        String json = String.format(
                "{\"timestamp\":\"%s\",\"status\":403,\"error\":\"Forbidden\",\"message\":\"%s\"}",
                Instant.now(),
                DEFAULT_FORBIDDEN_MESSAGE
        );

        response.getWriter().write(json);
    }
}
