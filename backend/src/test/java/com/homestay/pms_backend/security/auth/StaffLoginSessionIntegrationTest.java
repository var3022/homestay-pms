package com.homestay.pms_backend.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.AuthCookieProperties;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.jwt.JwtProperties;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import com.homestay.pms_backend.security.refreshtoken.RefreshTokenGenerator;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.5.3: Staff Login Session Integration Tests")
class StaffLoginSessionIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/v1/auth/login";
    private static final String REFRESH_ENDPOINT = "/api/v1/auth/refresh";
    private static final String LOGOUT_ENDPOINT = "/api/v1/auth/logout";
    private static final String TEST_DOMAIN = "@test-loginsession.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String UNTRUSTED_ORIGIN = "https://malicious-phishing.example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private RefreshTokenGenerator tokenGenerator;

    @Autowired
    private AuthCookieProperties cookieProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Environment environment;

    private JdbcTemplate adminJdbcTemplate;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

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
        adminJdbcTemplate = new JdbcTemplate(adminDs);

        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
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
                ) VALUES (?, ?, ?, 'Staff', 'Tester', '+919876543210', ?, ?, 0, ?, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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

    private String extractCookieValue(String setCookieHeader, String cookieName) {
        assertThat(setCookieHeader).isNotBlank();
        for (String part : setCookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(cookieName + "=")) {
                return trimmed.substring((cookieName + "=").length());
            }
        }
        throw new AssertionError("Cookie " + cookieName + " not found in Set-Cookie header: " + setCookieHeader);
    }

    // =========================================================================
    // 1. Successful Login & Refresh-Token Issuance
    // =========================================================================

    @Test
    @DisplayName("Success: Valid login returns HTTP 200, JWT contract, HttpOnly refresh cookie, and no-store headers")
    void shouldLoginSuccessfullyWithRefreshCookieAndJwt() throws Exception {
        String email = "success" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        MvcResult result = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("User-Agent", "Mozilla/5.0 StaffTestClient"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(cookieProperties.getCookieName() + "=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.expiresInSeconds").isNumber())
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.staff.firstName").value("Staff"))
                .andExpect(jsonPath("$.staff.lastName").value("Tester"))
                .andReturn();

        // 1. Verify JSON response body
        String responseBody = result.getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String accessToken = jsonNode.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // 2. Validate cryptographically that the returned JWT is signed and valid
        StaffJwtClaims claims = accessTokenService.validateAccessToken(accessToken);
        assertThat(claims.staffUserId()).isEqualTo(staffId);
        assertThat(claims.tokenType()).isEqualTo("access");

        // 3. Extract raw refresh token from Set-Cookie header
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String rawRefreshToken = extractCookieValue(setCookie, cookieProperties.getCookieName());
        assertThat(rawRefreshToken).isNotBlank();
        assertThat(tokenGenerator.isValidTokenFormat(rawRefreshToken)).isTrue();

        // 4. Assert raw refresh token is strictly ABSENT from JSON response body
        assertThat(responseBody).doesNotContain(rawRefreshToken);

        // 5. Assert database session: exactly 1 record with sequence_number=1, not revoked
        List<Map<String, Object>> sessions = adminJdbcTemplate.queryForList(
                "SELECT id, token_hash, token_family, sequence_number, is_revoked, expires_at, ip_address FROM staff_refresh_tokens WHERE staff_user_id = ?",
                staffId
        );
        assertThat(sessions).hasSize(1);
        Map<String, Object> session = sessions.get(0);
        assertThat(session.get("sequence_number")).isEqualTo(1);
        assertThat(session.get("is_revoked")).isEqualTo(false);
        assertThat(session.get("token_family")).isNotNull();
        assertThat((Timestamp) session.get("expires_at")).isNotNull();

        // 6. Assert raw refresh token is NOT stored in database (stored as SHA-256 hash)
        String storedHash = (String) session.get("token_hash");
        assertThat(storedHash).isNotEqualTo(rawRefreshToken);
        assertThat(storedHash).isEqualTo(tokenGenerator.hashToken(rawRefreshToken));

        // 7. Assert exactly one LOGIN_SUCCESS event recorded in audit log
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email, staff_user_id FROM staff_security_audit_events WHERE email = ?",
                email.toLowerCase()
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("event_type")).isEqualTo("LOGIN_SUCCESS");
        assertThat(auditEvents.get(0).get("staff_user_id")).isEqualTo(staffId);
    }

    // =========================================================================
    // 2. Failure Cases: No Session Created for Invalid/Deactivated/Locked Staff
    // =========================================================================

    @Test
    @DisplayName("Failure: Invalid password returns HTTP 401 and creates no refresh session")
    void shouldRejectInvalidPasswordAndCreateNoSession() throws Exception {
        String email = "badpass" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, "WrongPassword#999");

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Verify NO refresh token created in database
        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Failure: Nonexistent staff returns HTTP 401 and creates no refresh session")
    void shouldRejectNonexistentStaffAndCreateNoSession() throws Exception {
        String email = "unknown" + TEST_DOMAIN;
        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Verify NO refresh tokens created
        Integer totalSessions = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens",
                Integer.class
        );
        assertThat(totalSessions).isEqualTo(0);
    }

    @Test
    @DisplayName("Failure: Deactivated staff returns HTTP 401 and creates no refresh session")
    void shouldRejectDeactivatedStaffAndCreateNoSession() throws Exception {
        String email = "deactivated" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, false, false, null); // Deactivated

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        // Verify NO refresh token created in database
        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Failure: Locked staff returns generic HTTP 401 and creates no refresh session")
    void shouldRejectLockedStaffAndCreateNoSession() throws Exception {
        String email = "locked" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, true, Instant.now().plusSeconds(900)); // Actively locked

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        // Verify NO refresh token created in database
        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(0);
    }

    // =========================================================================
    // 3. CSRF & Origin Validation Protection on Login
    // =========================================================================

    @Test
    @DisplayName("Origin Defense: Missing Origin header on login is rejected with 403 Forbidden without audit or session")
    void shouldRejectMissingOriginOnLoginWithoutAuditOrSession() throws Exception {
        String email = "noorigin" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))) // No Origin header
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));

        // Verify NO session created in database
        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(0);

        // Verify NO login audit recorded
        Integer auditCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_security_audit_events WHERE email = ?",
                Integer.class,
                email.toLowerCase()
        );
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Origin Defense: Untrusted Origin header on login is rejected with 403 Forbidden without audit or session")
    void shouldRejectUntrustedOriginOnLoginWithoutAuditOrSession() throws Exception {
        String email = "untrusted" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Origin", UNTRUSTED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));

        // Verify NO session created in database
        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(0);

        // Verify NO login audit recorded
        Integer auditCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_security_audit_events WHERE email = ?",
                Integer.class,
                email.toLowerCase()
        );
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Origin Defense: Configured trusted origins are accepted on login")
    void shouldAcceptConfiguredTrustedOrigins() throws Exception {
        String[] trustedOrigins = {"http://localhost:3000", "http://127.0.0.1:3000", "http://localhost:8080"};

        for (int i = 0; i < trustedOrigins.length; i++) {
            String email = "trusted" + i + TEST_DOMAIN;
            createTestStaff(email, true, false, null);

            StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

            mockMvc.perform(post(LOGIN_ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Origin", trustedOrigins[i]))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(cookieProperties.getCookieName() + "=")));
        }
    }

    // =========================================================================
    // 4. End-to-End Session Lifecycle: Login -> Refresh -> Replay & Logout
    // =========================================================================

    @Test
    @DisplayName("Lifecycle: Login creates session, refresh rotates cookie, old cookie triggers replay detection, and logout revokes")
    void shouldCompleteFullSessionLifecycle() throws Exception {
        String email = "lifecycle" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        // 1. Initial Login
        StaffLoginRequest loginRequest = new StaffLoginRequest(email, DEFAULT_PASSWORD);
        MvcResult loginResult = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        String loginCookieHeader = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String rawToken1 = extractCookieValue(loginCookieHeader, cookieProperties.getCookieName());
        assertThat(rawToken1).isNotBlank();

        // 2. Immediately refreshing with the login cookie rotates successfully
        MvcResult refreshResult = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken1))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andReturn();

        String refreshCookieHeader = refreshResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String rawToken2 = extractCookieValue(refreshCookieHeader, cookieProperties.getCookieName());
        assertThat(rawToken2).isNotBlank();
        assertThat(rawToken2).isNotEqualTo(rawToken1);

        // Assert database state: sequence 1 revoked as ROTATED, sequence 2 active
        List<Map<String, Object>> dbTokens = adminJdbcTemplate.queryForList(
                "SELECT sequence_number, is_revoked, revoked_reason FROM staff_refresh_tokens WHERE staff_user_id = ? ORDER BY sequence_number ASC",
                staffId
        );
        assertThat(dbTokens).hasSize(2);
        assertThat(dbTokens.get(0).get("sequence_number")).isEqualTo(1);
        assertThat(dbTokens.get(0).get("is_revoked")).isEqualTo(true);
        assertThat(dbTokens.get(0).get("revoked_reason")).isEqualTo("ROTATED");

        assertThat(dbTokens.get(1).get("sequence_number")).isEqualTo(2);
        assertThat(dbTokens.get(1).get("is_revoked")).isEqualTo(false);

        // 3. The original refresh token (rawToken1) is unusable
        // Shift initial token's revoked_at beyond the 10-second grace window to trigger replay attack revocation
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '20 seconds' WHERE staff_user_id = ? AND sequence_number = 1",
                staffId
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken1))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Invalid or expired session"));

        // Assert entire token family revoked with REPLAY_ATTACK_DETECTED
        List<Map<String, Object>> replayTokens = adminJdbcTemplate.queryForList(
                "SELECT sequence_number, is_revoked, revoked_reason FROM staff_refresh_tokens WHERE staff_user_id = ? ORDER BY sequence_number ASC",
                staffId
        );
        assertThat(replayTokens.get(1).get("sequence_number")).isEqualTo(2);
        assertThat(replayTokens.get(1).get("is_revoked")).isEqualTo(true);
        assertThat(replayTokens.get(1).get("revoked_reason")).isEqualTo("REPLAY_ATTACK_DETECTED");

        // 4. Token 2 is now also unusable because the entire family was revoked
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken2))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());

        // 5. Fresh login establishes a new independent session family
        MvcResult loginResult2 = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        String rawToken3 = extractCookieValue(loginResult2.getResponse().getHeader(HttpHeaders.SET_COOKIE), cookieProperties.getCookieName());

        // 6. Logout revokes the login-created session family
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken3))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        // 7. Refresh after logout fails
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken3))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 5. Password-Change Boundary Invalidation
    // =========================================================================

    @Test
    @DisplayName("Boundary: Password reset invalidates previously issued refresh session on next refresh")
    void shouldInvalidatePreviouslyIssuedRefreshSessionOnPasswordReset() throws Exception {
        String email = "pwreset" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        // 1. Successful Login
        StaffLoginRequest loginRequest = new StaffLoginRequest(email, DEFAULT_PASSWORD);
        MvcResult loginResult = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        String rawToken = extractCookieValue(loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE), cookieProperties.getCookieName());

        // 2. Administrative password reset in database via fn_admin_reset_staff_password
        String newHash = passwordEncoder.encode("NewPassword#2026!");
        adminJdbcTemplate.execute((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT fn_admin_reset_staff_password(?, ?, ?)")) {
                ps.setObject(1, staffId);
                ps.setString(2, newHash);
                ps.setString(3, "admin-actor");
                ps.execute();
                return null;
            }
        });

        // 3. Refresh with the previously issued login cookie must fail closed
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired session"));
    }

    // =========================================================================
    // 6. Idempotency & Duplicate Prevention
    // =========================================================================

    @Test
    @DisplayName("Idempotency: Single login request creates exactly one session in database")
    void shouldNotCreateDuplicateSessionsOnSingleLoginRequest() throws Exception {
        String email = "noduplicate" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest loginRequest = new StaffLoginRequest(email, DEFAULT_PASSWORD);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        Integer sessionCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE staff_user_id = ?",
                Integer.class,
                staffId
        );
        assertThat(sessionCount).isEqualTo(1);
    }
}
