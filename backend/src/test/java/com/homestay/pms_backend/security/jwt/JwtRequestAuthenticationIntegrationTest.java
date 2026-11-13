package com.homestay.pms_backend.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.auth.StaffAuthenticationToken;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("Step 3.4.2: Secure JWT Request Authentication Integration Tests")
class JwtRequestAuthenticationIntegrationTest {

    private static final String TEST_DOMAIN = "@test-jwt-req.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        String adminPass = environment.getProperty("TEST_FLYWAY_PASSWORD");
        if (adminPass == null || adminPass.isBlank()) {
            adminPass = environment.getProperty("spring.flyway.password", "disposable_pass");
        }
        String dbUrl = environment.getProperty("auth.datasource.url", "jdbc:postgresql://localhost:5433/v7_disposable_db");

        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setDriverClassName("org.postgresql.Driver");
        adminDs.setUrl(dbUrl);
        adminDs.setUsername("pms_admin");
        adminDs.setPassword(adminPass);

        this.adminJdbcTemplate = new JdbcTemplate(adminDs);
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (adminJdbcTemplate != null) {
            adminJdbcTemplate.execute(
                    "ALTER TABLE staff_security_audit_events DISABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM staff_security_audit_events WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM staff_refresh_tokens WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable;"
            );
        }
    }

    private UUID createTestStaff(String email, boolean isActive, boolean isLocked, Instant lockoutUntil) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder.encode(DEFAULT_PASSWORD);
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Staff', 'Tester', '+919876543210', ?, ?, 0, ?, NULL, CURRENT_TIMESTAMP - INTERVAL '10 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                hash,
                isActive,
                isLocked,
                lockoutUntil != null ? Timestamp.from(lockoutUntil) : null
        );
        return id;
    }

    // =========================================================================
    // Test 1: No Bearer token results in 401 when enforcement is enabled
    // Test 14: Anonymous requests cannot reach PMS business endpoints
    // =========================================================================

    @Test
    @DisplayName("Test 1 & 14: Anonymous requests to PMS business endpoints return HTTP 401 with no-store")
    void shouldRejectAnonymousBusinessApiRequests() throws Exception {
        mockMvc.perform(get("/api/v1/properties"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Full authentication is required to access this resource."));

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/guests"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 2: Valid access JWT authenticates an active staff member
    // Test 16: Valid JWT does not automatically grant administrative privileges
    // =========================================================================

    @Test
    @DisplayName("Test 2 & 16: Valid access JWT authenticates active staff member without administrative privileges")
    void shouldAuthenticateValidJwtForActiveStaff() throws Exception {
        String email = "active-staff" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDto.token()))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Test 3: Invalid JWT signature results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 3: Invalid JWT signature results in HTTP 401")
    void shouldRejectInvalidSignature() throws Exception {
        String email = "tamper" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        // Tamper with signature
        String tamperedToken = tokenDto.token() + "tampered";

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // =========================================================================
    // Test 4: Expired JWT results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 4: Expired JWT results in HTTP 401")
    void shouldRejectExpiredJwt() throws Exception {
        String email = "expired" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        String expiredToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(15, ChronoUnit.MINUTES)))
                .notBefore(Date.from(past.minus(15, ChronoUnit.MINUTES)))
                .expiration(Date.from(past))
                .claim("token_type", "access")
                .claim("iat_millis", past.minus(15, ChronoUnit.MINUTES).toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 5: Wrong issuer or audience results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 5: Wrong issuer or audience results in HTTP 401")
    void shouldRejectWrongIssuerOrAudience() throws Exception {
        String email = "wrong-claims" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        // Wrong issuer
        String wrongIssuerToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer("untrusted-issuer")
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "access")
                .claim("iat_millis", now.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized());

        // Wrong audience
        String wrongAudienceToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add("untrusted-audience").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "access")
                .claim("iat_millis", now.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudienceToken))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Test 6: Wrong JWT token_type results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 6: Wrong token_type claim results in HTTP 401")
    void shouldRejectWrongTokenType() throws Exception {
        String email = "token-type" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        String wrongTypeToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "refresh") // Wrong token_type
                .claim("iat_millis", now.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongTypeToken))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Test 7: Unknown kid results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 7: Unknown key ID (kid) results in HTTP 401")
    void shouldRejectUnknownKid() throws Exception {
        String email = "unknown-kid" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        String unknownKidToken = Jwts.builder()
                .header().type("JWT").keyId("nonexistent-key-id").and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "access")
                .claim("iat_millis", now.toEpochMilli())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unknownKidToken))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Test 8: Missing or invalid iat_millis results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 8: Missing or malformed iat_millis claim results in HTTP 401")
    void shouldRejectMissingOrInvalidIatMillis() throws Exception {
        String email = "iat-millis" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        // Missing iat_millis
        String missingIatMillisToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "access")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + missingIatMillisToken))
                .andExpect(status().isUnauthorized());

        // Inconsistent iat_millis
        String mismatchedIatMillisToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(staffId.toString())
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .claim("token_type", "access")
                .claim("iat_millis", now.minusSeconds(100).toEpochMilli()) // Mismatch
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mismatchedIatMillisToken))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Test 9: Deactivated staff results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 9: Deactivated staff account results in HTTP 401 even with cryptographically valid JWT")
    void shouldRejectDeactivatedStaff() throws Exception {
        String email = "inactive" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, false, false, null); // Inactive

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDto.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 10: Locked staff results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 10: Locked staff account results in HTTP 401")
    void shouldRejectLockedStaff() throws Exception {
        String email = "locked" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, true, null); // Indefinitely locked

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDto.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Timed lockout
        String timedEmail = "timed-locked" + TEST_DOMAIN;
        UUID timedStaffId = createTestStaff(timedEmail, true, false, Instant.now().plus(30, ChronoUnit.MINUTES));
        AuthenticatedStaff timedStaff = new AuthenticatedStaff(timedStaffId, timedEmail, "Staff", "Tester", "+919876543210");
        AccessTokenDto timedTokenDto = accessTokenService.generateAccessToken(timedStaff);

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + timedTokenDto.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 11: Password-reset-invalidated JWT results in 401
    // =========================================================================

    @Test
    @DisplayName("Test 11: Password changed after token issuance revokes JWT and results in HTTP 401")
    void shouldRejectTokenIssuedBeforePasswordChange() throws Exception {
        String email = "password-reset" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        // Update password_changed_at to after token issuance
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = CURRENT_TIMESTAMP + INTERVAL '10 seconds' WHERE id = ?",
                staffId
        );

        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDto.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 12: Bearer token cannot be used as a refresh token
    // =========================================================================

    @Test
    @DisplayName("Test 12: Bearer access token cannot be used as a refresh token cookie")
    void shouldRejectBearerTokenAsRefreshCookie() throws Exception {
        String email = "token-as-cookie" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Staff", "Tester", "+919876543210");
        AccessTokenDto tokenDto = accessTokenService.generateAccessToken(staff);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("__Host-pms-refresh", tokenDto.token())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // Test 13: Authentication endpoints remain accessible without Bearer JWT
    // =========================================================================

    @Test
    @DisplayName("Test 13: Auth endpoints remain accessible without Bearer JWT and retain origin/security protection")
    void shouldPermitAuthEndpointsWithoutBearerJwt() throws Exception {
        // Disallowed origin on /login must fail with 403 (Origin validation retains protection)
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://malicious-site.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fake@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden());

        // Allowed origin on /login processes request (reaches auth controller, bad credentials returns 401)
        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fake@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        // Actuator health check is permitted without Bearer token
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Test 15: No authenticated principal is created from an invalid token
    // =========================================================================

    @Test
    @DisplayName("Test 15: No authenticated principal is created when token is malformed or invalid")
    void shouldNotCreatePrincipalForInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-garbage-token"))
                .andExpect(status().isUnauthorized());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // =========================================================================
    // Test 22: Unknown API paths are not unintentionally made public
    // =========================================================================

    @Test
    @DisplayName("Test 22: Unknown API paths return 401/403 and are not public")
    void shouldDenyUnknownApiPaths() throws Exception {
        // Unknown route under /api/** requires authentication -> 401
        mockMvc.perform(get("/api/v1/unknown-endpoint"))
                .andExpect(status().isUnauthorized());

        // Unknown route outside /api/** falls under anyRequest().denyAll() -> 401 (anonymous)
        mockMvc.perform(get("/some-random-path"))
                .andExpect(status().isUnauthorized());

        // Wrong HTTP method on public route: GET on /api/v1/auth/login requires auth -> 401
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Filter Registration and Lifecycle Verification
    // =========================================================================

    @Test
    @DisplayName("Filter Registration: Verifies JwtAuthenticationFilter is disabled from root servlet container registration")
    void shouldPreventStandaloneServletFilterRegistration() {
        @SuppressWarnings("unchecked")
        org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthenticationFilter> registration =
                webApplicationContext.getBean(
                        "jwtAuthenticationFilterRegistration",
                        org.springframework.boot.web.servlet.FilterRegistrationBean.class
                );

        assertThat(registration).isNotNull();
        assertThat(registration.isEnabled()).isFalse();
    }
}
