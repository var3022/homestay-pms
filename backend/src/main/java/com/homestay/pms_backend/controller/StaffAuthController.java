package com.homestay.pms_backend.controller;

import com.homestay.pms_backend.config.AuthApiEnabledCondition;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.dto.response.LogoutResponse;
import com.homestay.pms_backend.dto.response.StaffInfoDto;
import com.homestay.pms_backend.dto.response.StaffLoginResponse;
import com.homestay.pms_backend.dto.response.TokenRefreshResponse;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.StaffAuthAccount;
import com.homestay.pms_backend.security.StaffAuthenticationService;
import com.homestay.pms_backend.security.StaffIdentityLookup;
import com.homestay.pms_backend.security.auth.ClientContext;
import com.homestay.pms_backend.security.auth.ClientContextExtractor;
import com.homestay.pms_backend.security.auth.OriginValidator;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.refreshtoken.AuthCookieManager;
import com.homestay.pms_backend.security.refreshtoken.InvalidRefreshTokenException;
import com.homestay.pms_backend.security.refreshtoken.RefreshTokenService;
import com.homestay.pms_backend.security.refreshtoken.RotatedRefreshToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for staff authentication.
 *
 * Exposes:
 * - POST /api/v1/auth/login: Authenticates credentials and issues signed JWT access token.
 * - POST /api/v1/auth/refresh: Rotates HttpOnly refresh cookie, revalidates account state, and issues new JWT.
 * - POST /api/v1/auth/logout: Revokes refresh token family and clears HttpOnly refresh cookie.
 *
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

    private static final Logger log = LoggerFactory.getLogger(StaffAuthController.class);

    private final StaffAuthenticationService staffAuthenticationService;
    private final AccessTokenService accessTokenService;
    private final ClientContextExtractor clientContextExtractor;
    private final RefreshTokenService refreshTokenService;
    private final StaffIdentityLookup staffIdentityLookup;
    private final AuthCookieManager cookieManager;
    private final OriginValidator originValidator;
    private final Clock clock;

    @Autowired
    public StaffAuthController(
            StaffAuthenticationService staffAuthenticationService,
            AccessTokenService accessTokenService,
            ClientContextExtractor clientContextExtractor,
            @Autowired(required = false) RefreshTokenService refreshTokenService,
            @Autowired(required = false) StaffIdentityLookup staffIdentityLookup,
            @Autowired(required = false) AuthCookieManager cookieManager,
            @Autowired(required = false) OriginValidator originValidator,
            @Autowired(required = false) Clock clock
    ) {
        this.staffAuthenticationService = staffAuthenticationService;
        this.accessTokenService = accessTokenService;
        this.clientContextExtractor = clientContextExtractor;
        this.refreshTokenService = refreshTokenService;
        this.staffIdentityLookup = staffIdentityLookup;
        this.cookieManager = cookieManager;
        this.originValidator = originValidator;
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    public StaffAuthController(
            StaffAuthenticationService staffAuthenticationService,
            AccessTokenService accessTokenService,
            ClientContextExtractor clientContextExtractor
    ) {
        this(staffAuthenticationService, accessTokenService, clientContextExtractor, null, null, null, null, Clock.systemUTC());
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

    /**
     * Rotates the refresh token stored in the HttpOnly cookie, re-verifies staff account state,
     * and issues a new JWT access token along with a replacement refresh cookie.
     *
     * @param httpRequest Inbound servlet request containing the refresh cookie and origin header
     * @return HTTP 200 with new JWT access token and replacement refresh cookie; or HTTP 401 on invalid session
     */
    @PostMapping(
            value = "/refresh",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<TokenRefreshResponse> refresh(
            HttpServletRequest httpRequest
    ) {
        if (refreshTokenService == null || cookieManager == null || originValidator == null || staffIdentityLookup == null) {
            throw new IllegalStateException("Authentication refresh subsystem is not fully configured");
        }

        // 1. Strict Origin / CSRF validation
        originValidator.validate(httpRequest);

        // 2. Read and validate raw refresh token exclusively from HttpOnly cookie
        String rawToken = cookieManager.extractRefreshToken(httpRequest);

        // 3. Extract verified client context
        ClientContext context = clientContextExtractor.extract(httpRequest);

        // Record refresh start timestamp before database rotation to detect concurrent credential mutations
        Instant refreshStartedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        // 4. Atomically rotate the token in the database (commits inside transaction template)
        RotatedRefreshToken rotated = refreshTokenService.rotateToken(
                rawToken,
                context.ipAddress(),
                context.userAgent()
        );

        UUID staffUserId = rotated.staffUserId();

        // 5. Authoritatively revalidate staff account state over pms_auth
        StaffAuthAccount account = staffIdentityLookup.findById(staffUserId)
                .orElseThrow(() -> {
                    log.warn("Revoking successor token: staff account [{}] no longer exists", staffUserId);
                    refreshTokenService.revokeSession(rotated.rawToken(), staffUserId, "system:auth");
                    return new InvalidRefreshTokenException("Staff account does not exist");
                });

        if (!account.isActive()) {
            log.warn("Revoking successor token: staff account [{}] is deactivated", staffUserId);
            refreshTokenService.revokeSession(rotated.rawToken(), staffUserId, "system:auth");
            throw new InvalidRefreshTokenException("Staff account is deactivated");
        }

        if (account.isLocked()) {
            Instant lockoutUntil = account.lockoutUntil();
            if (lockoutUntil == null || lockoutUntil.isAfter(clock.instant())) {
                log.warn("Revoking successor token: staff account [{}] is locked until {}", staffUserId, lockoutUntil);
                refreshTokenService.revokeSession(rotated.rawToken(), staffUserId, "system:auth");
                throw new InvalidRefreshTokenException("Staff account is locked");
            }
        }

        // Concurrent credential mutation check:
        // Fail closed if password was changed at or after the refresh attempt began
        if (account.passwordChangedAt() == null || !account.passwordChangedAt().isBefore(refreshStartedAt)) {
            log.warn("Revoking successor token: staff account [{}] password changed at or after refresh started (changedAt={}, startedAt={})",
                    staffUserId, account.passwordChangedAt(), refreshStartedAt);
            refreshTokenService.revokeSession(rotated.rawToken(), staffUserId, "system:auth");
            throw new InvalidRefreshTokenException("Staff account credentials invalid or changed");
        }

        // 6. Generate signed JWT access token
        AuthenticatedStaff staff = new AuthenticatedStaff(
                account.id(),
                account.email(),
                account.firstName(),
                account.lastName(),
                account.phone()
        );

        AccessTokenDto tokenDto;
        try {
            tokenDto = accessTokenService.generateAccessToken(staff);
        } catch (Exception e) {
            log.error("JWT token generation failed during refresh; revoking successor token: {}", e.getMessage());
            refreshTokenService.revokeSession(rotated.rawToken(), staffUserId, "system:auth");
            throw e;
        }

        // 7. Assemble replacement HttpOnly cookie
        ResponseCookie replacementCookie = cookieManager.createRefreshCookie(rotated.rawToken());

        // 8. Assemble response DTO
        long expiresInSeconds = Math.max(0, Duration.between(Instant.now(), tokenDto.expiresAt()).toSeconds());
        StaffInfoDto staffInfo = new StaffInfoDto(
                staff.id(),
                staff.email(),
                staff.firstName(),
                staff.lastName()
        );

        TokenRefreshResponse response = new TokenRefreshResponse(
                tokenDto.token(),
                tokenDto.tokenType(),
                expiresInSeconds,
                staffInfo
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, replacementCookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    /**
     * Revokes the refresh-token session family associated with the presented HttpOnly cookie
     * and clears the client refresh cookie.
     *
     * @param httpRequest Inbound servlet request containing the refresh cookie and origin header
     * @return HTTP 200 with confirmation message and expired refresh cookie
     */
    @PostMapping(
            value = "/logout",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<LogoutResponse> logout(
            HttpServletRequest httpRequest
    ) {
        if (refreshTokenService == null || cookieManager == null || originValidator == null) {
            throw new IllegalStateException("Authentication logout subsystem is not fully configured");
        }

        // 1. Strict Origin / CSRF validation
        originValidator.validate(httpRequest);

        // 2. Read optional refresh token from cookie
        Optional<String> maybeToken = cookieManager.extractOptionalRefreshToken(httpRequest);

        // 3. If present, revoke the corresponding session family idempotently
        if (maybeToken.isPresent()) {
            refreshTokenService.revokeSession(maybeToken.get(), null, "system:auth");
        }

        // 4. Always issue removal cookie to clear browser storage
        ResponseCookie clearCookie = cookieManager.createRemovalCookie();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new LogoutResponse("Logged out successfully"));
    }
}
