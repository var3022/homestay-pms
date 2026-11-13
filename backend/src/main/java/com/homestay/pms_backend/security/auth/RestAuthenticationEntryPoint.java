package com.homestay.pms_backend.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * REST Authentication Entry Point returning standardized JSON 401 Unauthorized responses.
 *
 * Ensures:
 * 1. Safe, non-leaking error message ("Full authentication is required to access this resource.").
 * 2. Does not expose JWT signatures, internal parsing errors, or database exception details.
 * 3. SecurityContextHolder is explicitly cleared.
 * 4. Cache-Control: no-store and Pragma: no-cache are set on the response.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);
    private static final String DEFAULT_UNAUTHORIZED_MESSAGE = "Full authentication is required to access this resource.";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        SecurityContextHolder.clearContext();
        log.warn("Unauthorized access attempt to [{}]: {}", request.getRequestURI(), authException.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");

        String json = String.format(
                "{\"timestamp\":\"%s\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}",
                Instant.now(),
                DEFAULT_UNAUTHORIZED_MESSAGE
        );

        response.getWriter().write(json);
    }
}
