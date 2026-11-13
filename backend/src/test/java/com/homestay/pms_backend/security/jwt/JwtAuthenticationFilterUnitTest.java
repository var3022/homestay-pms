package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.auth.StaffAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Step 3.4.2: JwtAuthenticationFilter Unit Tests")
class JwtAuthenticationFilterUnitTest {

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private StaffAuthenticationStateValidator stateValidator;

    @Mock
    private ObjectProvider<AccessTokenService> accessTokenServiceProvider;

    @Mock
    private ObjectProvider<StaffAuthenticationStateValidator> stateValidatorProvider;

    @Mock
    private AuthenticationEntryPoint authenticationEntryPoint;

    @Mock
    private SecurityEnforcementProperties enforcementProperties;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    private final UUID staffUserId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new JwtAuthenticationFilter(
                accessTokenServiceProvider,
                stateValidatorProvider,
                authenticationEntryPoint,
                enforcementProperties
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Public Routes: shouldNotFilter returns true for permitted public auth and health endpoints")
    void shouldSkipPublicRoutes() {
        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletRequest refreshReq = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        MockHttpServletRequest logoutReq = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(loginReq)).isTrue();
        assertThat(filter.shouldNotFilter(refreshReq)).isTrue();
        assertThat(filter.shouldNotFilter(logoutReq)).isTrue();
        assertThat(filter.shouldNotFilter(healthReq)).isTrue();

        // Wrong method on auth endpoint must NOT be skipped
        MockHttpServletRequest loginGetReq = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        assertThat(filter.shouldNotFilter(loginGetReq)).isFalse();

        // Business APIs must NOT be skipped
        MockHttpServletRequest roomsReq = new MockHttpServletRequest("GET", "/api/v1/rooms");
        MockHttpServletRequest propReq = new MockHttpServletRequest("GET", "/api/v1/properties");
        assertThat(filter.shouldNotFilter(roomsReq)).isFalse();
        assertThat(filter.shouldNotFilter(propReq)).isFalse();
    }

    @Test
    @DisplayName("Missing Authorization Header: Proceeds down filter chain without populating SecurityContext")
    void shouldProceedWhenNoAuthHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationEntryPoint, never()).commence(any(), any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Malformed Scheme: Rejects Authorization header not starting with 'Bearer '")
    void shouldRejectMalformedScheme() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationEntryPoint).commence(eq(request), eq(response), any());
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Blank Bearer Token: Rejects 'Bearer ' without token payload")
    void shouldRejectEmptyBearerToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationEntryPoint).commence(eq(request), eq(response), any());
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Invalid Token Cryptography: Rejects token when AccessTokenService throws JwtValidationException")
    void shouldRejectInvalidJwtToken() throws ServletException, IOException {
        when(accessTokenServiceProvider.getIfAvailable()).thenReturn(accessTokenService);
        when(stateValidatorProvider.getIfAvailable()).thenReturn(stateValidator);
        when(accessTokenService.validateAccessToken("invalid-token"))
                .thenThrow(new JwtValidationException("Signature verification failed"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationEntryPoint).commence(eq(request), eq(response), any());
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Deactivated or Locked Account: Rejects token when state validator returns empty")
    void shouldRejectInactiveOrLockedStaff() throws ServletException, IOException {
        Instant now = Instant.now();
        StaffJwtClaims claims = new StaffJwtClaims(
                staffUserId, "pms-auth", "pms-app",
                now, now, now.plusSeconds(900), "jti-1", "access", "k1"
        );

        when(accessTokenServiceProvider.getIfAvailable()).thenReturn(accessTokenService);
        when(stateValidatorProvider.getIfAvailable()).thenReturn(stateValidator);
        when(accessTokenService.validateAccessToken("valid-token")).thenReturn(claims);
        when(stateValidator.validateAndGetPrincipal(staffUserId, now)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(authenticationEntryPoint).commence(eq(request), eq(response), any());
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Valid Token & Active Account: Populates SecurityContext with empty authorities and proceeds")
    void shouldAuthenticateValidTokenAndProceed() throws ServletException, IOException {
        Instant now = Instant.now();
        StaffJwtClaims claims = new StaffJwtClaims(
                staffUserId, "pms-auth", "pms-app",
                now, now, now.plusSeconds(900), "jti-1", "access", "k1"
        );
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(
                staffUserId, "staff@homestay.com", "John", "Doe"
        );

        when(accessTokenServiceProvider.getIfAvailable()).thenReturn(accessTokenService);
        when(stateValidatorProvider.getIfAvailable()).thenReturn(stateValidator);
        when(accessTokenService.validateAccessToken("valid-token")).thenReturn(claims);
        when(stateValidator.validateAndGetPrincipal(staffUserId, now)).thenReturn(Optional.of(principal));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authenticationEntryPoint, never()).commence(any(), any(), any());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth).isInstanceOf(StaffAuthenticationToken.class);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.getCredentials()).isNull();
        // Crucial security requirement: authorities must be strictly empty (no administrative grant from JWT)
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("Database Infrastructure Failure: Returns sanitized HTTP 500 when database throws DataAccessException")
    void shouldReturn500WhenDatabaseFailsDuringRevalidation() throws ServletException, IOException {
        Instant now = Instant.now();
        StaffJwtClaims claims = new StaffJwtClaims(
                staffUserId, "pms-auth", "pms-app",
                now, now, now.plusSeconds(900), "jti-1", "access", "k1"
        );

        when(accessTokenServiceProvider.getIfAvailable()).thenReturn(accessTokenService);
        when(stateValidatorProvider.getIfAvailable()).thenReturn(stateValidator);
        when(accessTokenService.validateAccessToken("valid-token")).thenReturn(claims);
        when(stateValidator.validateAndGetPrincipal(staffUserId, now))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection timeout"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/rooms");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(authenticationEntryPoint, never()).commence(any(), any(), any());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"status\":500");
        assertThat(response.getContentAsString()).contains("An internal database error occurred. Please try again later.");
        // Internal exception message must NEVER be leaked in response body
        assertThat(response.getContentAsString()).doesNotContain("PostgreSQL connection timeout");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
