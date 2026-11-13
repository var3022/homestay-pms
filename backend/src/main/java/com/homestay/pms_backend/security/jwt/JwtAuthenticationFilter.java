package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.auth.StaffAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Enterprise JWT Access-Token Request Authentication Filter.
 *
 * Intercepts incoming HTTP requests to PMS business endpoints:
 * 1. Skips public authentication and health-check endpoints.
 * 2. Parses Authorization: Bearer <access-token>.
 * 3. Rejects malformed Authorization headers safely.
 * 4. Cryptographically validates token via AccessTokenService (HS256, kid, iss, aud, exp, iat_millis).
 * 5. Revalidates current staff account state (active, unlocked, password_changed_at < iat_millis)
 *    via authoritative pms_auth DataSource.
 * 6. Populates Spring SecurityContext with minimal AuthenticatedStaffPrincipal.
 * 7. Fails closed on any validation error and clears SecurityContext.
 * 8. Never logs access tokens, Authorization headers, secrets, or password hashes.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectProvider<AccessTokenService> accessTokenServiceProvider;
    private final ObjectProvider<StaffAuthenticationStateValidator> stateValidatorProvider;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityEnforcementProperties enforcementProperties;

    public JwtAuthenticationFilter(
            ObjectProvider<AccessTokenService> accessTokenServiceProvider,
            ObjectProvider<StaffAuthenticationStateValidator> stateValidatorProvider,
            AuthenticationEntryPoint authenticationEntryPoint,
            SecurityEnforcementProperties enforcementProperties
    ) {
        this.accessTokenServiceProvider = accessTokenServiceProvider;
        this.stateValidatorProvider = stateValidatorProvider;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.enforcementProperties = enforcementProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            if ("/api/v1/auth/login".equals(path) ||
                "/api/v1/auth/refresh".equals(path) ||
                "/api/v1/auth/logout".equals(path)) {
                return true;
            }
        }

        if ("GET".equalsIgnoreCase(method)) {
            if ("/actuator/health".equals(path) || path.startsWith("/actuator/health/")) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // If no Authorization header is present:
        if (authHeader == null || authHeader.isBlank()) {
            // Let the filter chain proceed. If enforcement is enabled, Spring Security's
            // authorization manager will reject unauthenticated access via AuthenticationEntryPoint.
            filterChain.doFilter(request, response);
            return;
        }

        // Malformed Authorization header check:
        // Must start with "Bearer " (case-sensitive per RFC 6750) and contain non-empty token
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Rejected request with malformed/unsupported Authorization header scheme");
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new InsufficientAuthenticationException("Malformed or unsupported Authorization header.")
            );
            return;
        }

        String rawToken = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            log.warn("Rejected request with empty Bearer token");
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new InsufficientAuthenticationException("Bearer token must not be blank.")
            );
            return;
        }

        AccessTokenService tokenService = accessTokenServiceProvider.getIfAvailable();
        StaffAuthenticationStateValidator validator = stateValidatorProvider.getIfAvailable();

        if (tokenService == null || validator == null) {
            log.error("JWT token service or staff state validator is not available in application context");
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new InsufficientAuthenticationException("Authentication subsystem is temporarily unavailable.")
            );
            return;
        }

        StaffJwtClaims claims;
        try {
            claims = tokenService.validateAccessToken(rawToken);
        } catch (JwtValidationException e) {
            log.warn("Rejected invalid JWT access token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new BadCredentialsException("Invalid, expired, or malformed access token.")
            );
            return;
        } catch (Exception e) {
            log.warn("Unexpected failure parsing access token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new BadCredentialsException("Failed to validate access token.")
            );
            return;
        }

        // Revalidate staff state against pms_auth DataSource
        Optional<AuthenticatedStaffPrincipal> principalOpt;
        try {
            principalOpt = validator.validateAndGetPrincipal(claims.staffUserId(), claims.issuedAt());
        } catch (DataAccessException e) {
            log.error("Internal database access exception while revalidating staff account state for [{}]: {}",
                    claims.staffUserId(), e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            String json = String.format(
                    "{\"timestamp\":\"%s\",\"status\":500,\"error\":\"Internal Server Error\",\"message\":\"An internal database error occurred. Please try again later.\"}",
                    Instant.now()
            );
            response.getWriter().write(json);
            return;
        } catch (Exception e) {
            log.warn("Unexpected failure revalidating staff state for [{}]: {}", claims.staffUserId(), e.getMessage());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new BadCredentialsException("Staff account is deactivated, locked, revoked, or no longer exists.")
            );
            return;
        }

        if (principalOpt.isEmpty()) {
            log.warn("Staff account [{}] state validation failed (inactive/locked/password-changed)", claims.staffUserId());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response,
                    new BadCredentialsException("Staff account is deactivated, locked, revoked, or no longer exists.")
            );
            return;
        }

        // Token and staff state are valid: populate SecurityContext
        AuthenticatedStaffPrincipal principal = principalOpt.get();
        StaffAuthenticationToken authentication = new StaffAuthenticationToken(principal, claims);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        filterChain.doFilter(request, response);
    }
}
