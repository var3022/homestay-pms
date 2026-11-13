package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.config.AuthApiEnabledCondition;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.dto.response.StaffInfoDto;
import com.homestay.pms_backend.dto.response.StaffLoginResponse;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.StaffAuthenticationService;
import com.homestay.pms_backend.security.auth.ClientContext;
import com.homestay.pms_backend.security.auth.ClientContextExtractor;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * REST controller for staff authentication.
 *
 * Exposes POST /api/v1/auth/login to authenticate staff members and issue signed JWT access tokens.
 * Conditionally registered only when both auth.datasource.enabled=true and jwt.enabled=true.
 *
 * SECURITY BOUNDARY NOTICE:
 * This authentication subsystem and backend endpoints are designated for isolated local/private
 * development use only until business API authorization (JWT request filter chain and RBAC) is enforced.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Conditional(AuthApiEnabledCondition.class)
public class StaffAuthController {

    private final StaffAuthenticationService staffAuthenticationService;
    private final AccessTokenService accessTokenService;
    private final ClientContextExtractor clientContextExtractor;

    public StaffAuthController(
            StaffAuthenticationService staffAuthenticationService,
            AccessTokenService accessTokenService,
            ClientContextExtractor clientContextExtractor
    ) {
        this.staffAuthenticationService = staffAuthenticationService;
        this.accessTokenService = accessTokenService;
        this.clientContextExtractor = clientContextExtractor;
    }

    /**
     * Authenticates staff credentials, asserts database state, and issues a signed JWT access token.
     *
     * @param request     Validated login request containing email and raw password
     * @param httpRequest Underlying servlet request for extracting safe IP and User-Agent
     * @return HTTP 200 with JWT access token, expiration, and minimal staff details; or HTTP 401 on failure
     */
    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<StaffLoginResponse> login(
            @Valid @RequestBody StaffLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        // 1. Extract safe client context
        ClientContext context = clientContextExtractor.extract(httpRequest);

        // 2. Call StaffAuthenticationService to verify credentials & record database audit
        AuthenticatedStaff staff = staffAuthenticationService.authenticate(
                request.email(),
                request.password(),
                context.ipAddress(),
                context.userAgent()
        );

        // 3. Call AccessTokenService to issue a signed JWT access token
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        // 4. Compute remaining TTL in seconds and assemble safe response DTO
        long expiresInSeconds = Math.max(0, Duration.between(Instant.now(), tokenDto.expiresAt()).toSeconds());
        StaffLoginResponse response = new StaffLoginResponse(
                tokenDto.token(),
                tokenDto.tokenType(),
                expiresInSeconds,
                new StaffInfoDto(
                        staff.id(),
                        staff.email(),
                        staff.firstName(),
                        staff.lastName()
                )
        );

        // 5. Return HTTP 200 with mandatory non-cacheable headers
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }
}
