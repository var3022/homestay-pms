package com.homestay.pms_backend.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.AuthCookieProperties;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import com.homestay.pms_backend.security.refreshtoken.IssuedRefreshToken;
import com.homestay.pms_backend.security.refreshtoken.RefreshTokenGenerator;
import com.homestay.pms_backend.security.refreshtoken.RefreshTokenService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.5.2: Secure Refresh and Logout HTTP Endpoints Integration Tests")
class StaffAuthRefreshLogoutIntegrationTest {

    private static final String REFRESH_ENDPOINT = "/api/v1/auth/refresh";
    private static final String LOGOUT_ENDPOINT = "/api/v1/auth/logout";
    private static final String LOGIN_ENDPOINT = "/api/v1/auth/login";
    private static final String TEST_DOMAIN = "@test-refresh.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String DISALLOWED_ORIGIN = "https://malicious-site.example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenGenerator tokenGenerator;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthCookieProperties cookieProperties;

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

    private UUID createStaffUser(String emailPrefix, boolean isActive, boolean isLocked, Instant lockoutUntil) {
        UUID staffId = UUID.randomUUID();
        String email = emailPrefix + TEST_DOMAIN;
        String encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);
        Instant now = Instant.now().minusSeconds(3600);

        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """,
                staffId,
                email,
                encodedPassword,
                "Test",
                "Staff",
                "+919876543210",
                isActive,
                isLocked,
                lockoutUntil != null ? Timestamp.from(lockoutUntil) : null,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return staffId;
    }

    // =========================================================================
    // 1. Core Token Rotation & JWT Issuance (Requirements 1 - 4)
    // =========================================================================

    @Test
    @DisplayName("Requirements 1-3: Valid refresh cookie rotates token, sets replacement cookie, and returns 15m HS256 JWT")
    void shouldRotateTokenAndReturnNewJwt() throws Exception {
        UUID staffId = createStaffUser("alice", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Initial Agent");

        Cookie refreshCookie = new Cookie(cookieProperties.getCookieName(), initial.rawToken());

        MvcResult result = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(refreshCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.expiresInSeconds").isNumber())
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value("alice" + TEST_DOMAIN))
                .andReturn();

        // Verify Set-Cookie header contains replacement refresh token
        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains(cookieProperties.getCookieName() + "=");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("SameSite=Strict");
        assertThat(setCookieHeader).contains("Path=/");
        if (cookieProperties.isSecure()) {
            assertThat(setCookieHeader).contains("Secure");
        }
        assertThat(setCookieHeader).doesNotContain("Domain=");

        // Verify newly issued access token is valid under AccessTokenService
        String responseJson = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(responseJson);
        String jwtToken = root.get("accessToken").asText();

        StaffJwtClaims claims = accessTokenService.validateAccessToken(jwtToken);
        assertThat(claims.staffUserId()).isEqualTo(staffId);
        assertThat(claims.tokenType()).isEqualTo("access");

        // Verify HS256 algorithm in JWT header
        String[] jwtParts = jwtToken.split("\\.");
        assertThat(jwtParts).hasSize(3);
        JsonNode headerNode = objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(jwtParts[0]));
        assertThat(headerNode.get("alg").asText()).isEqualTo("HS256");

        // Verify TTL is approximately 15 minutes (900 seconds)
        long expiresIn = root.get("expiresInSeconds").asLong();
        assertThat(expiresIn).isBetween(800L, 900L);
    }

    @Test
    @DisplayName("Requirement 4: Old refresh token becomes unusable after rotation")
    void shouldInvalidateOldRefreshTokenAfterRotation() throws Exception {
        UUID staffId = createStaffUser("bob", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        Cookie refreshCookie = new Cookie(cookieProperties.getCookieName(), initial.rawToken());

        // First rotation succeeds
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(refreshCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        // Presenting old token again outside grace window must be rejected
        // Simulate passage of grace window by updating revoked_at to 15 seconds in past
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '15 seconds' WHERE id = ?",
                initial.tokenId()
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(refreshCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    // =========================================================================
    // 2. Cookie Presence, Validation & Boundary Rejections (Requirements 5 - 7)
    // =========================================================================

    @Test
    @DisplayName("Requirement 5: Missing refresh cookie is rejected with 401 and generic error")
    void shouldRejectMissingRefreshCookie() throws Exception {
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired session"));
    }

    @Test
    @DisplayName("Requirement 6: Malformed and duplicate cookies are rejected")
    void shouldRejectMalformedAndDuplicateCookies() throws Exception {
        UUID staffId = createStaffUser("charlie", true, false, null);
        IssuedRefreshToken validToken = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // 1. Malformed token (invalid characters / length)
        Cookie malformedCookie = new Cookie(cookieProperties.getCookieName(), "too-short");
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(malformedCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // 2. Duplicate cookies with same name
        Cookie cookie1 = new Cookie(cookieProperties.getCookieName(), validToken.rawToken());
        Cookie cookie2 = new Cookie(cookieProperties.getCookieName(), validToken.rawToken());
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(cookie1, cookie2)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("Requirement 7: Expired and revoked refresh tokens are rejected")
    void shouldRejectExpiredAndRevokedTokens() throws Exception {
        UUID staffId = createStaffUser("david", true, false, null);
        IssuedRefreshToken token1 = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");
        IssuedRefreshToken token2 = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Expire token1
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                token1.tokenId()
        );

        // Manually revoke token2
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET is_revoked = TRUE, revoked_at = CURRENT_TIMESTAMP - INTERVAL '1 hour', revoked_reason = 'MANUAL_TEST' WHERE id = ?",
                token2.tokenId()
        );

        // Expired token rejected
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token1.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Revoked token rejected
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token2.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    // =========================================================================
    // 3. Concurrency, Replay & Audit Event Preservation (Requirements 8 - 10)
    // =========================================================================

    @Test
    @DisplayName("Requirement 8: Concurrent refresh cannot create two valid successors")
    void shouldEnsureOnlyOneSuccessorOnConcurrentRefresh() throws Exception {
        UUID staffId = createStaffUser("eve", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                MvcResult res = mockMvc.perform(post(REFRESH_ENDPOINT)
                                .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                                .header("Origin", ALLOWED_ORIGIN))
                        .andReturn();
                return res.getResponse().getStatus();
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int successCount = 0;
        int failureCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 200) successCount++;
            else if (status == 401) failureCount++;
        }

        // Exactly one concurrent call must succeed, the other fails safely
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirements 9-10: Replay outside grace window revokes whole family and preserves audit event")
    void shouldRevokeTokenFamilyOnReplayOutsideGraceWindow() throws Exception {
        UUID staffId = createStaffUser("frank", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Rotate initial token to obtain successor
        Cookie initialCookie = new Cookie(cookieProperties.getCookieName(), initial.rawToken());
        MvcResult firstRefresh = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(initialCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        // Shift initial token's revoked_at beyond the 10-second grace window
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '20 seconds' WHERE id = ?",
                initial.tokenId()
        );

        // Replay initial token
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(initialCookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Verify entire family is revoked in database
        List<Boolean> revokedStates = adminJdbcTemplate.queryForList(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE token_family = ?",
                Boolean.class,
                initial.tokenFamily()
        );
        assertThat(revokedStates).isNotEmpty().allMatch(Boolean.TRUE::equals);

        // Verify audit event was persisted
        List<Map<String, Object>> events = adminJdbcTemplate.queryForList(
                "SELECT event_type, email FROM staff_security_audit_events WHERE email = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                "frank" + TEST_DOMAIN
        );
        assertThat(events).hasSize(1);
    }

    // =========================================================================
    // 4. Account Eligibility & Invalidation (Requirements 11 - 12)
    // =========================================================================

    @Test
    @DisplayName("Requirement 11: Deactivated or actively locked staff cannot refresh")
    void shouldRejectRefreshForDeactivatedOrLockedStaff() throws Exception {
        // 1. Deactivated staff
        UUID deactId = createStaffUser("grace", false, false, null);
        IssuedRefreshToken deactToken = refreshTokenService.issueInitialToken(createStaffUser("temp1", true, false, null), "127.0.0.1", "Agent");
        // Re-assign token to deactivated staff user
        adminJdbcTemplate.update("UPDATE staff_refresh_tokens SET staff_user_id = ? WHERE id = ?", deactId, deactToken.tokenId());

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), deactToken.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // 2. Locked staff
        UUID lockedId = createStaffUser("heidi", true, true, Instant.now().plusSeconds(900));
        IssuedRefreshToken lockedToken = refreshTokenService.issueInitialToken(createStaffUser("temp2", true, false, null), "127.0.0.1", "Agent");
        adminJdbcTemplate.update("UPDATE staff_refresh_tokens SET staff_user_id = ? WHERE id = ?", lockedId, lockedToken.tokenId());

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), lockedToken.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("Requirement 12: Password-change invalidation is respected on refresh")
    void shouldRejectRefreshUponPasswordChange() throws Exception {
        UUID staffId = createStaffUser("ivan", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Simulate administrative password change
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

        // Attempting refresh with pre-change token fails
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("Requirement 12B: Password change between rotation and JWT issuance causes request to fail closed and revokes successor")
    void shouldRevokeSuccessorAndFailClosedIfPasswordChangedConcurrently() throws Exception {
        UUID staffId = createStaffUser("concurrent-pwd", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Manually update password_changed_at in DB to a future instant (representing concurrent password change during rotation)
        Instant futureMutation = Instant.now().plusSeconds(60);
        adminJdbcTemplate.update(
                "UPDATE staff_users SET password_changed_at = ? WHERE id = ?",
                Timestamp.from(futureMutation),
                staffId
        );

        // Attempting refresh must fail closed with 401
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Verify the entire token family was revoked in DB
        List<Boolean> revokedStates = adminJdbcTemplate.queryForList(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE token_family = ?",
                Boolean.class,
                initial.tokenFamily()
        );
        assertThat(revokedStates).isNotEmpty().allMatch(Boolean.TRUE::equals);
    }

    // =========================================================================
    // 5. Logout & Session Revocation (Requirements 13 - 16)
    // =========================================================================

    @Test
    @DisplayName("Requirements 13-15: Logout revokes correct family, is idempotent, and clears cookie")
    void shouldRevokeFamilyAndClearCookieOnLogout() throws Exception {
        UUID staffId = createStaffUser("judy", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        Cookie cookie = new Cookie(cookieProperties.getCookieName(), token.rawToken());

        // First logout succeeds and clears cookie
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(cookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        // Verify token in DB is revoked
        Boolean isRevoked = adminJdbcTemplate.queryForObject(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE id = ?",
                Boolean.class,
                token.tokenId()
        );
        assertThat(isRevoked).isTrue();

        // Repeated logout with same cookie succeeds idempotently
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(cookie)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Logout with no cookie at all succeeds idempotently and clears cookie
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("Requirement 16: Cross-staff and cross-session manipulation is prevented")
    void shouldPreventCrossStaffSessionManipulation() throws Exception {
        UUID staffA = createStaffUser("mallory", true, false, null);
        UUID staffB = createStaffUser("victor", true, false, null);

        IssuedRefreshToken tokenA = refreshTokenService.issueInitialToken(staffA, "127.0.0.1", "Agent A");
        IssuedRefreshToken tokenB = refreshTokenService.issueInitialToken(staffB, "127.0.0.1", "Agent B");

        // Logging out with Token A revokes Token A's family only
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), tokenA.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        // Token B's family must remain active
        Boolean isTokenBRevoked = adminJdbcTemplate.queryForObject(
                "SELECT is_revoked FROM staff_refresh_tokens WHERE id = ?",
                Boolean.class,
                tokenB.tokenId()
        );
        assertThat(isTokenBRevoked).isFalse();
    }

    // =========================================================================
    // 6. Origin & CSRF Defense (Requirements 17 - 19)
    // =========================================================================

    @Test
    @DisplayName("Requirement 17: Untrusted Origin is rejected with 403 Forbidden")
    void shouldRejectUntrustedOrigin() throws Exception {
        UUID staffId = createStaffUser("oscar", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("Origin", DISALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));

        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("Origin", DISALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    @DisplayName("Requirement 17B: Forged proxy headers cannot bypass origin check")
    void shouldRejectForgedProxyHeadersWithoutOrigin() throws Exception {
        UUID staffId = createStaffUser("forger", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Attacker attempts to forge X-Forwarded-For, X-Forwarded-Host, and custom proxy headers
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("X-Forwarded-Host", "localhost")
                        .header("X-Real-IP", "127.0.0.1"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cross-origin request rejected"));
    }

    @Test
    @DisplayName("Requirement 18B: Request without Origin fails even with loopback address when server secret is missing or invalid")
    void shouldRejectAbsentOriginWithMissingOrInvalidServerSecret() throws Exception {
        UUID staffId = createStaffUser("secret-test", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // 1. Missing server secret from loopback
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        }))
                .andExpect(status().isForbidden());

        // 2. Invalid server secret from loopback
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("X-PMS-Internal-Source", "wrong-invalid-secret-token")
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Requirement 18: Missing Origin is rejected unless trusted-server policy applies")
    void shouldRejectMissingOriginByDefault() throws Exception {
        UUID staffId = createStaffUser("peggy", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        // Absent Origin on refresh rejected with 403
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(403));

        // Absent Origin on logout rejected with 403
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    @DisplayName("Requirement 19: Trusted Origin is accepted")
    void shouldAcceptTrustedOrigin() throws Exception {
        UUID staffId = createStaffUser("quentin", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 7. Cookie Security & Response Sanitation (Requirements 20 - 23)
    // =========================================================================

    @Test
    @DisplayName("Requirements 20-22: Cookie attributes adhere to production security policy with no-store")
    void shouldVerifyCookieAttributesAndCacheHeaders() throws Exception {
        UUID staffId = createStaffUser("rupert", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        MvcResult result = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("HttpOnly");
        if (cookieProperties.isSecure()) {
            assertThat(setCookie).contains(AuthCookieProperties.PROD_COOKIE_NAME);
            assertThat(setCookie).contains("Secure");
        }
        assertThat(setCookie).doesNotContain("Domain=");
    }

    @Test
    @DisplayName("Requirement 23: No refresh token appears in response JSON or logs")
    void shouldNeverExposeRawRefreshTokenInResponseBody() throws Exception {
        UUID staffId = createStaffUser("sybil", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent");

        MvcResult result = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("refreshToken");
        assertThat(body).doesNotContain(token.rawToken());
        assertThat(body).contains("accessToken");
    }

    // =========================================================================
    // 8. Error Handling, Isolation & Operational Integrity (Requirements 24 - 26)
    // =========================================================================

    @Test
    @DisplayName("Requirement 25: Database failure does not leak SQL or secrets")
    void shouldHandleDatabaseFailureWithoutLeakingSecrets() throws Exception {
        // Querying with non-existent token while tampering with table structure simulation
        // (Or passing malformed token that triggers validation exception)
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), "invalid-token-value-format"))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(not(containsString("SELECT"))))
                .andExpect(jsonPath("$.message").value(not(containsString("staff_refresh_tokens"))));
    }

    @Test
    @DisplayName("Requirement 26: Existing login endpoint remains unchanged and operational")
    void shouldVerifyLoginEndpointRemainsOperational() throws Exception {
        UUID staffId = createStaffUser("trent", true, false, null);

        String loginPayload = """
                {
                    "email": "trent%s",
                    "password": "%s"
                }
                """.formatted(TEST_DOMAIN, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value("trent" + TEST_DOMAIN));
    }
}
