package com.homestay.pms_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.dto.response.StaffLoginResponse;
import com.homestay.pms_backend.exception.GlobalExceptionHandler;
import com.homestay.pms_backend.exception.OriginValidationException;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.StaffAuthenticationException;
import com.homestay.pms_backend.security.StaffAuthenticationService;
import com.homestay.pms_backend.security.StaffIdentityLookup;
import com.homestay.pms_backend.security.auth.ClientContext;
import com.homestay.pms_backend.security.auth.ClientContextExtractor;
import com.homestay.pms_backend.security.auth.OriginValidator;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.refreshtoken.AuthCookieManager;
import com.homestay.pms_backend.security.refreshtoken.IssuedRefreshToken;
import com.homestay.pms_backend.security.refreshtoken.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Step 3.3C.4: Staff Auth Controller Unit Tests")
class StaffAuthControllerUnitTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";

    @Mock
    private StaffAuthenticationService staffAuthenticationService;

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private ClientContextExtractor clientContextExtractor;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private StaffIdentityLookup staffIdentityLookup;

    @Mock
    private AuthCookieManager cookieManager;

    @Mock
    private OriginValidator originValidator;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        StaffAuthController controller = new StaffAuthController(
                staffAuthenticationService,
                accessTokenService,
                clientContextExtractor,
                refreshTokenService,
                staffIdentityLookup,
                cookieManager,
                originValidator,
                java.time.Clock.systemUTC()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(cookieManager))
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Unit: Successful orchestration calls auth service, issues refresh token, issues JWT, sets cookie, and returns 200")
    void shouldOrchestrateSuccessfulLogin() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = "mock-raw-refresh-token-43-chars-example-string";
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, "alice@example.com", "Alice", "Admin", "+919876543210");
        AccessTokenDto tokenDto = new AccessTokenDto("mock.jwt.token", Instant.now().plusSeconds(900), "mock-jti", "access");
        IssuedRefreshToken issuedToken = new IssuedRefreshToken(
                rawRefreshToken, UUID.randomUUID(), familyId, 1, Instant.now().plusSeconds(604800), staffId
        );
        ResponseCookie refreshCookie = ResponseCookie.from("__Host-pms-refresh", rawRefreshToken)
                .httpOnly(true).secure(true).path("/").sameSite("Strict").maxAge(java.time.Duration.ofDays(7)).build();

        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(staffAuthenticationService.authenticate(eq("alice@example.com"), eq("SecretPassword#1"), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(staff);
        when(refreshTokenService.issueInitialToken(eq(staffId), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(issuedToken);
        when(accessTokenService.generateAccessToken(staff)).thenReturn(tokenDto);
        when(cookieManager.createRefreshCookie(rawRefreshToken)).thenReturn(refreshCookie);

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("__Host-pms-refresh=" + rawRefreshToken)))
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value("alice@example.com"));

        verify(originValidator).validate(any());
        verify(staffAuthenticationService).authenticate("alice@example.com", "SecretPassword#1", "192.168.1.100", "Mozilla/5.0 UnitTester");
        verify(refreshTokenService).issueInitialToken(staffId, "192.168.1.100", "Mozilla/5.0 UnitTester");
        verify(accessTokenService).generateAccessToken(staff);
        verify(cookieManager).createRefreshCookie(rawRefreshToken);
    }

    @Test
    @DisplayName("Unit: Authentication failure prevents token service invocation and returns 401")
    void shouldHandleAuthExceptionWithoutTokenGeneration() throws Exception {
        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("127.0.0.1", "Unknown"));
        when(staffAuthenticationService.authenticate(any(), any(), any(), any()))
                .thenThrow(new StaffAuthenticationException());

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "WrongPassword#1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        verify(originValidator).validate(any());
        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(accessTokenService);
    }

    @Test
    @DisplayName("Unit: Database failure returns 500 without leaking SQL or internal details")
    void shouldHandleDatabaseFailureWithoutLeakingSql() throws Exception {
        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("127.0.0.1", "Unknown"));
        when(staffAuthenticationService.authenticate(any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("FATAL: database connection closed unexpectedly"));

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An internal database error occurred. Please try again later."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database connection closed"))));

        verify(originValidator).validate(any());
        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(accessTokenService);
    }

    @Test
    @DisplayName("Unit: Untrusted Origin on login is rejected with 403 Forbidden before authentication")
    void shouldRejectLoginOnUntrustedOrigin() throws Exception {
        doThrow(new OriginValidationException("Origin not allowed: https://evil.com"))
                .when(originValidator).validate(any());

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "https://evil.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));

        verify(originValidator).validate(any());
        verifyNoInteractions(staffAuthenticationService);
        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(accessTokenService);
    }

    @Test
    @DisplayName("Unit: JWT generation failure after refresh token issuance revokes session family")
    void shouldRevokeSessionFamilyWhenJwtGenerationFailsOnLogin() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = "mock-raw-refresh-token-jwt-failure";
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, "alice@example.com", "Alice", "Admin", "+919876543210");
        IssuedRefreshToken issuedToken = new IssuedRefreshToken(
                rawRefreshToken, UUID.randomUUID(), familyId, 1, Instant.now().plusSeconds(604800), staffId
        );

        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(staffAuthenticationService.authenticate(eq("alice@example.com"), eq("SecretPassword#1"), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(staff);
        when(refreshTokenService.issueInitialToken(eq(staffId), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(issuedToken);
        when(accessTokenService.generateAccessToken(staff)).thenThrow(new RuntimeException("Signing key unavailable"));

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        assertThatThrownBy(() -> mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000")))
                .hasCauseInstanceOf(RuntimeException.class);

        verify(refreshTokenService).revokeSession(rawRefreshToken, staffId, "system:auth");
        verify(cookieManager, never()).createRefreshCookie(any());
    }

    @Test
    @DisplayName("Unit: Cookie creation failure after refresh token issuance revokes session family")
    void shouldRevokeSessionFamilyWhenCookieCreationFailsOnLogin() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = "mock-raw-refresh-token-cookie-failure";
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, "alice@example.com", "Alice", "Admin", "+919876543210");
        AccessTokenDto tokenDto = new AccessTokenDto("mock.jwt.token", Instant.now().plusSeconds(900), "mock-jti", "access");
        IssuedRefreshToken issuedToken = new IssuedRefreshToken(
                rawRefreshToken, UUID.randomUUID(), familyId, 1, Instant.now().plusSeconds(604800), staffId
        );

        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(staffAuthenticationService.authenticate(eq("alice@example.com"), eq("SecretPassword#1"), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(staff);
        when(refreshTokenService.issueInitialToken(eq(staffId), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(issuedToken);
        when(accessTokenService.generateAccessToken(staff)).thenReturn(tokenDto);
        when(cookieManager.createRefreshCookie(rawRefreshToken)).thenThrow(new IllegalStateException("Insecure cookie failure"));

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        assertThatThrownBy(() -> mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000")))
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(refreshTokenService).revokeSession(rawRefreshToken, staffId, "system:auth");
    }

    @Test
    @DisplayName("Unit: If compensating revocation itself fails, the request still fails closed without exposing secrets")
    void shouldFailClosedWhenCompensatingRevocationThrowsExceptionOnLogin() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = "mock-raw-refresh-token-double-failure";
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, "alice@example.com", "Alice", "Admin", "+919876543210");
        IssuedRefreshToken issuedToken = new IssuedRefreshToken(
                rawRefreshToken, UUID.randomUUID(), familyId, 1, Instant.now().plusSeconds(604800), staffId
        );

        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(staffAuthenticationService.authenticate(eq("alice@example.com"), eq("SecretPassword#1"), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(staff);
        when(refreshTokenService.issueInitialToken(eq(staffId), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(issuedToken);
        when(accessTokenService.generateAccessToken(staff)).thenThrow(new RuntimeException("Primary JWT failure"));
        org.mockito.Mockito.doThrow(new RuntimeException("Database error during revocation"))
                .when(refreshTokenService).revokeSession(any(), any(), any());

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        assertThatThrownBy(() -> mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", "http://localhost:3000")))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("Primary JWT failure");

        verify(refreshTokenService).revokeSession(rawRefreshToken, staffId, "system:auth");
        verify(cookieManager, never()).createRefreshCookie(any());
    }

    @Test
    @DisplayName("Unit: Subsystem dependencies missing causes safe fail-closed IllegalStateException")
    void shouldFailSafelyWhenSubsystemDependenciesMissing() {
        StaffAuthController incompleteController = new StaffAuthController(
                staffAuthenticationService,
                accessTokenService,
                clientContextExtractor,
                null,
                null,
                null,
                null,
                null
        );

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> incompleteController.login(request, httpRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication login subsystem is not fully configured");
    }

    @Test
    @DisplayName("Unit: Successful refresh rotates token, revalidates staff, issues JWT, sets replacement cookie, and returns 200")
    void shouldOrchestrateSuccessfulRefresh() throws Exception {
        UUID staffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String oldToken = "old-raw-refresh-token-43-chars-placeholder";
        String newToken = "new-raw-refresh-token-43-chars-placeholder";

        com.homestay.pms_backend.security.refreshtoken.RotatedRefreshToken rotated =
                new com.homestay.pms_backend.security.refreshtoken.RotatedRefreshToken(
                        newToken, UUID.randomUUID(), familyId, 2, Instant.now().plusSeconds(604800), staffId
                );

        com.homestay.pms_backend.security.StaffAuthAccount account =
                new com.homestay.pms_backend.security.StaffAuthAccount(
                        staffId, "alice@example.com", "hash", "Alice", "Admin", "+919876543210",
                        true, false, 0, null, null, Instant.now().minusSeconds(3600), Instant.now(), Instant.now()
                );

        AccessTokenDto tokenDto = new AccessTokenDto("new.jwt.access.token", Instant.now().plusSeconds(900), "mock-jti-2", "access");
        org.springframework.http.ResponseCookie replacementCookie =
                org.springframework.http.ResponseCookie.from("__Host-pms-refresh", newToken)
                        .httpOnly(true).secure(true).path("/").sameSite("Strict").maxAge(java.time.Duration.ofDays(7)).build();

        when(cookieManager.extractRefreshToken(any())).thenReturn(oldToken);
        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(refreshTokenService.rotateToken(eq(oldToken), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester"))).thenReturn(rotated);
        when(staffIdentityLookup.findById(staffId)).thenReturn(java.util.Optional.of(account));
        when(accessTokenService.generateAccessToken(any())).thenReturn(tokenDto);
        when(cookieManager.createRefreshCookie(newToken)).thenReturn(replacementCookie);

        mockMvc.perform(post(REFRESH_URL)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("__Host-pms-refresh=" + newToken)))
                .andExpect(jsonPath("$.accessToken").value("new.jwt.access.token"))
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value("alice@example.com"));

        verify(originValidator).validate(any());
        verify(refreshTokenService).rotateToken(oldToken, "192.168.1.100", "Mozilla/5.0 UnitTester");
        verify(accessTokenService).generateAccessToken(any());
    }

    @Test
    @DisplayName("Unit: Missing refresh cookie is rejected with 401 and clears cookie")
    void shouldRejectRefreshOnMissingCookie() throws Exception {
        org.springframework.http.ResponseCookie clearCookie =
                org.springframework.http.ResponseCookie.from("__Host-pms-refresh", "")
                        .httpOnly(true).secure(true).path("/").sameSite("Strict").maxAge(java.time.Duration.ZERO).build();

        when(cookieManager.extractRefreshToken(any()))
                .thenThrow(new com.homestay.pms_backend.security.refreshtoken.InvalidRefreshTokenException("Missing refresh token cookie"));
        when(cookieManager.createRemovalCookie()).thenReturn(clearCookie);

        mockMvc.perform(post(REFRESH_URL)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired session"));

        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(accessTokenService);
    }

    @Test
    @DisplayName("Unit: Untrusted Origin throws OriginValidationException returning 403 Forbidden")
    void shouldRejectRefreshOnUntrustedOrigin() throws Exception {
        org.mockito.Mockito.doThrow(new com.homestay.pms_backend.exception.OriginValidationException("Origin not allowed: https://evil.com"))
                .when(originValidator).validate(any());

        mockMvc.perform(post(REFRESH_URL)
                        .header("Origin", "https://evil.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));

        verifyNoInteractions(cookieManager);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("Unit: Successful logout revokes session family, clears cookie, and returns 200")
    void shouldOrchestrateSuccessfulLogout() throws Exception {
        String rawToken = "some-refresh-token-for-logout";
        org.springframework.http.ResponseCookie clearCookie =
                org.springframework.http.ResponseCookie.from("__Host-pms-refresh", "")
                        .httpOnly(true).secure(true).path("/").sameSite("Strict").maxAge(java.time.Duration.ZERO).build();

        when(cookieManager.extractOptionalRefreshToken(any())).thenReturn(java.util.Optional.of(rawToken));
        when(cookieManager.createRemovalCookie()).thenReturn(clearCookie);

        mockMvc.perform(post(LOGOUT_URL)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        verify(originValidator).validate(any());
        verify(refreshTokenService).revokeSession(rawToken, null, "system:auth");
    }

    @Test
    @DisplayName("Unit: Logout without cookie succeeds idempotently and clears cookie")
    void shouldHandleLogoutIdempotentlyWithoutCookie() throws Exception {
        org.springframework.http.ResponseCookie clearCookie =
                org.springframework.http.ResponseCookie.from("__Host-pms-refresh", "")
                        .httpOnly(true).secure(true).path("/").sameSite("Strict").maxAge(java.time.Duration.ZERO).build();

        when(cookieManager.extractOptionalRefreshToken(any())).thenReturn(java.util.Optional.empty());
        when(cookieManager.createRemovalCookie()).thenReturn(clearCookie);

        mockMvc.perform(post(LOGOUT_URL)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        verify(originValidator).validate(any());
        verifyNoInteractions(refreshTokenService);
    }
}
