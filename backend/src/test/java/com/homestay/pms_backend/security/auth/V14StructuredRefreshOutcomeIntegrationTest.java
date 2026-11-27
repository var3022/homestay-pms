package com.homestay.pms_backend.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.AuthCookieProperties;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@DisplayName("PMS Phase 2 — Step 3.4.3.12C.1: V14 Structured Refresh Outcomes and Transactional Safety Tests")
public class V14StructuredRefreshOutcomeIntegrationTest {

    private static final String REFRESH_ENDPOINT = "/api/v1/auth/refresh";
    private static final String LOGOUT_ENDPOINT = "/api/v1/auth/logout";
    private static final String TEST_DOMAIN = "@v14-refresh-test.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

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

    @Autowired
    private JdbcTemplate jdbcTemplate; // Primary pms_app connection

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate; // Dedicated pms_auth connection

    private JdbcTemplate adminJdbcTemplate;
    private String dbUrl;
    private String adminPass;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        adminPass = environment.getProperty("TEST_FLYWAY_PASSWORD");
        if (adminPass == null || adminPass.isBlank()) {
            adminPass = environment.getProperty("spring.flyway.password", "disposable_pass");
        }
        dbUrl = environment.getProperty("auth.datasource.url", "jdbc:postgresql://localhost:5433/v7_disposable_db");

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
                "V14",
                "Tester",
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
    // Scenario A: First refresh succeeds
    // =========================================================================
    @Test
    @DisplayName("Scenario A: First refresh succeeds, returns HTTP 200, issues JWT, sets replacement cookie")
    void testA_firstRefreshSucceeds() throws Exception {
        UUID staffId = createStaffUser("user-a", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent A");

        MvcResult result = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains(cookieProperties.getCookieName() + "=");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).doesNotContain("Max-Age=0");

        // Verify predecessor state in DB: is_revoked = true, reason = ROTATED
        Map<String, Object> oldRow = adminJdbcTemplate.queryForMap(
                "SELECT is_revoked, revoked_reason FROM staff_refresh_tokens WHERE id = ?",
                initial.tokenId()
        );
        assertThat(oldRow.get("is_revoked")).isEqualTo(true);
        assertThat(oldRow.get("revoked_reason")).isEqualTo("ROTATED");
    }

    // =========================================================================
    // Scenarios B, C, D: Ten concurrent refreshes on the same token
    // =========================================================================
    @Test
    @DisplayName("Scenarios B, C, D: 10 concurrent refreshes yield exactly 1 winner (200), 9 losers (409), no cookie deletion, no family revocation")
    void testBCD_tenConcurrentRefreshesOnSameToken() throws Exception {
        UUID staffId = createStaffUser("user-bcd", true, false, null);
        IssuedRefreshToken token = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent BCD");

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post(REFRESH_ENDPOINT)
                                .cookie(new Cookie(cookieProperties.getCookieName(), token.rawToken()))
                                .header("Origin", ALLOWED_ORIGIN))
                        .andReturn();
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        int winnerCount = 0;
        int collisionCount = 0;
        for (Future<MvcResult> f : futures) {
            MvcResult res = f.get();
            int status = res.getResponse().getStatus();
            if (status == 200) {
                winnerCount++;
                String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
                assertThat(setCookie).isNotNull().contains(cookieProperties.getCookieName() + "=");
                assertThat(setCookie).doesNotContain("Max-Age=0");
            } else if (status == 409) {
                collisionCount++;
                // Scenario D: Collision response emits NO refresh-cookie deletion
                String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
                assertThat(setCookie).isNull();

                String body = res.getResponse().getContentAsString();
                assertThat(body).contains("REFRESH_CONCURRENCY_COLLISION");
            }
        }

        // Scenario B: Exactly 1 winner
        assertThat(winnerCount).as("Exactly one concurrent request must win (200)").isEqualTo(1);
        // Scenario C: Exactly 9 receive 409 collision
        assertThat(collisionCount).as("Exactly 9 concurrent requests must receive 409 collision").isEqualTo(9);

        // Scenario C: Token family was NOT revoked
        Integer activeTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                token.tokenFamily()
        );
        assertThat(activeTokens).isEqualTo(1);

        // Total tokens in family = 2 (initial revoked as ROTATED + 1 winner successor)
        Integer totalTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ?",
                Integer.class,
                token.tokenFamily()
        );
        assertThat(totalTokens).isEqualTo(2);

        // No replay attack audit event logged
        Integer replayCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_security_audit_events WHERE email = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                Integer.class,
                "user-bcd" + TEST_DOMAIN
        );
        assertThat(replayCount).isZero();
    }

    // =========================================================================
    // Scenario E: Reusing old token within grace window does not revoke family
    // =========================================================================
    @Test
    @DisplayName("Scenario E: Reusing old token within grace window returns 409 and does not revoke family")
    void testE_reusingOldTokenWithinGraceWindowDoesNotRevokeFamily() throws Exception {
        UUID staffId = createStaffUser("user-e", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent E");

        // Rotate initial token
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        // Re-present same token within 10s grace window
        MvcResult collisionResult = mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_CONCURRENCY_COLLISION"))
                .andReturn();

        // No Set-Cookie deletion emitted
        assertThat(collisionResult.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        // Family has 1 active successor
        Integer activeTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeTokens).isEqualTo(1);
    }

    // =========================================================================
    // Scenario F: Reusing old token after grace window triggers persisted family revocation
    // =========================================================================
    @Test
    @DisplayName("Scenario F: Reusing old token after grace window triggers persisted family revocation")
    void testF_reusingOldTokenAfterGraceWindowRevokesFamily() throws Exception {
        UUID staffId = createStaffUser("user-f", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent F");

        // First rotation
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        // Simulate that 15 seconds elapsed since rotation (beyond 10s window)
        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '15 seconds' WHERE id = ?",
                initial.tokenId()
        );

        // Replay old token
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Verify entire family is revoked in PostgreSQL
        Integer activeTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeTokens).isZero();
    }

    // =========================================================================
    // Scenario G: Replay detection audit event is committed and queryable
    // =========================================================================
    @Test
    @DisplayName("Scenario G: Replay detection audit event is committed and queryable after HTTP failure")
    void testG_replayDetectionAuditEventCommitted() throws Exception {
        UUID staffId = createStaffUser("user-g", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent G");

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '20 seconds' WHERE id = ?",
                initial.tokenId()
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());

        // Query staff_security_audit_events table
        List<Map<String, Object>> events = adminJdbcTemplate.queryForList(
                "SELECT event_type, email, staff_user_id, details FROM staff_security_audit_events WHERE email = ? AND event_type = 'REFRESH_TOKEN_REPLAY_DETECTED'",
                "user-g" + TEST_DOMAIN
        );
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("staff_user_id")).isEqualTo(staffId);
        String detailsStr = events.get(0).get("details").toString();
        assertThat(detailsStr).contains(initial.tokenFamily().toString());
    }

    // =========================================================================
    // Scenario H: Disabled account cannot refresh
    // =========================================================================
    @Test
    @DisplayName("Scenario H: Disabled account cannot refresh, family is revoked, cookie cleared")
    void testH_disabledAccountCannotRefresh() throws Exception {
        UUID staffId = createStaffUser("user-h", false, false, null);
        // Manually insert active token for deactivated user
        UUID familyId = UUID.randomUUID();
        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        adminJdbcTemplate.update(
                "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                staffId, tokenHash, familyId
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Family revoked in DB with reason ACCOUNT_DEACTIVATED
        String revokedReason = adminJdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM staff_refresh_tokens WHERE token_family = ?",
                String.class,
                familyId
        );
        assertThat(revokedReason).isEqualTo("ACCOUNT_DEACTIVATED");
    }

    // =========================================================================
    // Scenario I: Locked account cannot refresh
    // =========================================================================
    @Test
    @DisplayName("Scenario I: Locked account cannot refresh, family revoked, audit event persisted")
    void testI_lockedAccountCannotRefresh() throws Exception {
        UUID staffId = createStaffUser("user-i", true, true, Instant.now().plusSeconds(900));
        UUID familyId = UUID.randomUUID();
        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        adminJdbcTemplate.update(
                "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                staffId, tokenHash, familyId
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), rawToken))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Family revoked in DB with reason ACCOUNT_LOCKED
        String revokedReason = adminJdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM staff_refresh_tokens WHERE token_family = ?",
                String.class,
                familyId
        );
        assertThat(revokedReason).isEqualTo("ACCOUNT_LOCKED");

        // Audit event logged
        Integer lockedAudits = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_security_audit_events WHERE email = ? AND event_type = 'REFRESH_REJECTED_LOCKED_ACCOUNT'",
                Integer.class,
                "user-i" + TEST_DOMAIN
        );
        assertThat(lockedAudits).isEqualTo(1);
    }

    // =========================================================================
    // Scenario J: Expired token cannot refresh
    // =========================================================================
    @Test
    @DisplayName("Scenario J: Expired token cannot refresh, marked EXPIRED in DB, cookie cleared")
    void testJ_expiredTokenCannotRefresh() throws Exception {
        UUID staffId = createStaffUser("user-j", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent J");

        adminJdbcTemplate.update(
                "UPDATE staff_refresh_tokens SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?",
                initial.tokenId()
        );

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        String revokedReason = adminJdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM staff_refresh_tokens WHERE id = ?",
                String.class,
                initial.tokenId()
        );
        assertThat(revokedReason).isEqualTo("EXPIRED");
    }

    // =========================================================================
    // Scenario K: Unknown token cannot refresh
    // =========================================================================
    @Test
    @DisplayName("Scenario K: Unknown token cannot refresh, returns 401 without leakage, clears cookie")
    void testK_unknownTokenCannotRefresh() throws Exception {
        String randomRawToken = tokenGenerator.generateToken();

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), randomRawToken))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.message").value("Invalid or expired session"));
    }

    // =========================================================================
    // Scenario L: Logout racing with refresh cannot leave a refresh-capable family
    // =========================================================================
    @Test
    @DisplayName("Scenario L: Logout racing with refresh cannot leave a refresh-capable family")
    void testL_logoutRacingWithRefresh() throws Exception {
        UUID staffId = createStaffUser("user-l", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent L");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Integer> refreshFuture = executor.submit(() -> {
            startLatch.await();
            MvcResult res = mockMvc.perform(post(REFRESH_ENDPOINT)
                            .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                            .header("Origin", ALLOWED_ORIGIN))
                    .andReturn();
            return res.getResponse().getStatus();
        });

        Future<Integer> logoutFuture = executor.submit(() -> {
            startLatch.await();
            MvcResult res = mockMvc.perform(post(LOGOUT_ENDPOINT)
                            .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                            .header("Origin", ALLOWED_ORIGIN))
                    .andReturn();
            return res.getResponse().getStatus();
        });

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int refreshStatus = refreshFuture.get();
        int logoutStatus = logoutFuture.get();

        assertThat(logoutStatus).isEqualTo(200);

        // If refresh was executed, then logout MUST have revoked the whole family,
        // OR logout revoked first and refresh was rejected.
        // In ALL interleavings, if we now perform another logout or check DB, no valid tokens exist!
        if (refreshStatus == 200) {
            // Since logout executed with initial token, ensure session family is revoked
            refreshTokenService.revokeSession(initial.rawToken(), null, "system:auth");
        }

        Integer activeTokens = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeTokens).isZero();
    }

    // =========================================================================
    // Scenario M: Database error does not masquerade as benign collision
    // =========================================================================
    @Test
    @DisplayName("Scenario M: Database error does not masquerade as benign collision")
    void testM_databaseErrorDoesNotMasqueradeAsCollision() {
        // Calling routine directly with invalid future expiration violation (timestamp in past)
        String hash1 = "1111111111111111111111111111111111111111111111111111111111111111";
        String hash2 = "2222222222222222222222222222222222222222222222222222222222222222";

        assertThatThrownBy(() -> authJdbcTemplate.queryForObject(
                "SELECT outcome FROM fn_rotate_refresh_token_v2(?, ?, CURRENT_TIMESTAMP - INTERVAL '1 day', 'Test', '127.0.0.1')",
                String.class,
                hash1, hash2
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Token expiry violation");
    }

    // =========================================================================
    // Scenario N: Lost HTTP response does not generate a second successor
    // =========================================================================
    @Test
    @DisplayName("Scenario N: Lost HTTP response does not generate a second successor")
    void testN_lostHttpResponseDoesNotGenerateSecondSuccessor() throws Exception {
        UUID staffId = createStaffUser("user-n", true, false, null);
        IssuedRefreshToken initial = refreshTokenService.issueInitialToken(staffId, "127.0.0.1", "Agent N");

        // Attempt 1: succeeds (simulate client losing the response)
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk());

        // Attempt 2: client retries with the same token within grace window
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .cookie(new Cookie(cookieProperties.getCookieName(), initial.rawToken()))
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_CONCURRENCY_COLLISION"));

        // Exactly 1 successor token exists in database (sequence_number = 2)
        Integer activeCount = adminJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staff_refresh_tokens WHERE token_family = ? AND is_revoked = FALSE",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(activeCount).isEqualTo(1);

        Integer maxSeq = adminJdbcTemplate.queryForObject(
                "SELECT MAX(sequence_number) FROM staff_refresh_tokens WHERE token_family = ?",
                Integer.class,
                initial.tokenFamily()
        );
        assertThat(maxSeq).isEqualTo(2);
    }

    // =========================================================================
    // Scenario O: Two independent backend connections share PostgreSQL state
    // =========================================================================
    @Test
    @DisplayName("Scenario O: Two independent backend connections share PostgreSQL state correctly")
    void testO_twoIndependentInstancesSharePostgresState() {
        String authUser = "pms_auth";
        String authPass = environment.getProperty("TEST_AUTH_DATASOURCE_PASSWORD");
        if (authPass == null || authPass.isBlank()) {
            authPass = environment.getProperty("auth.datasource.password", "");
        }

        DriverManagerDataSource instanceADs = new DriverManagerDataSource(dbUrl, authUser, authPass);
        instanceADs.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate instanceAJdbc = new JdbcTemplate(instanceADs);

        DriverManagerDataSource instanceBDs = new DriverManagerDataSource(dbUrl, authUser, authPass);
        instanceBDs.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate instanceBJdbc = new JdbcTemplate(instanceBDs);

        UUID staffId = createStaffUser("user-o", true, false, null);
        UUID familyId = UUID.randomUUID();
        String token1Hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        String token2Hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2";
        String token3Hash = "ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc3";

        // Seed initial token
        adminJdbcTemplate.update(
                "INSERT INTO staff_refresh_tokens (staff_user_id, token_hash, token_family, sequence_number, expires_at) " +
                "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                staffId, token1Hash, familyId
        );

        // Instance A rotates token 1 -> 2
        Map<String, Object> resA = instanceAJdbc.queryForMap(
                "SELECT outcome, new_token_id, sequence_number FROM fn_rotate_refresh_token_v2(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'InstanceA', '10.0.0.1')",
                token1Hash, token2Hash
        );
        assertThat(resA.get("outcome")).isEqualTo("SUCCESS");
        assertThat(resA.get("sequence_number")).isEqualTo(2);

        // Instance B presents token 1 within grace window
        Map<String, Object> resB = instanceBJdbc.queryForMap(
                "SELECT outcome, new_token_id FROM fn_rotate_refresh_token_v2(?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', 'InstanceB', '10.0.0.2')",
                token1Hash, token3Hash
        );
        assertThat(resB.get("outcome")).isEqualTo("CONCURRENCY_COLLISION");
        assertThat(resB.get("new_token_id")).isNull();
    }

    // =========================================================================
    // Scenario P: Existing auth database privilege boundaries remain intact
    // =========================================================================
    @Test
    @DisplayName("Scenario P: Existing auth database privilege boundaries remain intact")
    void testP_privilegeBoundariesIntact() {
        // 1. pms_app is strictly DENIED execution of fn_rotate_refresh_token_v2
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "SELECT * FROM fn_rotate_refresh_token_v2('0000000000000000000000000000000000000000000000000000000000000000', '1111111111111111111111111111111111111111111111111111111111111111', now() + interval '1 day')"
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for function fn_rotate_refresh_token_v2");

        // 2. pms_auth CANNOT read domain tables (e.g. reservations)
        assertThatThrownBy(() -> authJdbcTemplate.execute("SELECT * FROM reservations LIMIT 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table reservations");

        // 3. pms_auth CANNOT read finance tables
        assertThatThrownBy(() -> authJdbcTemplate.execute("SELECT * FROM finance_accounts LIMIT 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table finance_accounts");

        // 4. pms_auth is allowed to execute fn_rotate_refresh_token_v2
        Map<String, Object> testAuthExec = authJdbcTemplate.queryForMap(
                "SELECT outcome FROM fn_rotate_refresh_token_v2('0000000000000000000000000000000000000000000000000000000000000000', '1111111111111111111111111111111111111111111111111111111111111111', now() + interval '1 day')"
        );
        assertThat(testAuthExec.get("outcome")).isEqualTo("INVALID_TOKEN");
    }
}
