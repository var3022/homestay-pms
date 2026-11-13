package com.homestay.pms_backend.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.jwt.JwtProperties;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@DisplayName("Step 3.3C.4: Secure Staff Login HTTP API Integration Tests")
class StaffLoginIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/v1/auth/login";
    private static final String TEST_DOMAIN = "@test-login.com";
    private static final String DEFAULT_PASSWORD = "StrongSecurePassword#2026!";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtProperties jwtProperties;

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

    // =========================================================================
    // 1. Successful Authentication & JWT Token Issuance
    // =========================================================================

    @Test
    @DisplayName("Success: Valid credentials return HTTP 200 with signed 15-minute JWT and no-store headers")
    void shouldLoginSuccessfullyWithValidCredentials() throws Exception {
        String email = "alice" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        MvcResult result = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", "Mozilla/5.0 StaffTestClient"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.expiresInSeconds").isNumber())
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.staff.firstName").value("Staff"))
                .andExpect(jsonPath("$.staff.lastName").value("Tester"))
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = jsonNode.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // 1. Validate cryptographically that the returned token is signed by our key and valid
        StaffJwtClaims claims = accessTokenService.validateAccessToken(accessToken);
        assertThat(claims.staffUserId()).isEqualTo(staffId);
        assertThat(claims.tokenType()).isEqualTo("access");
        assertThat(claims.issuer()).isEqualTo("homestay-pms-test");

        // 2. Assert exact JWT structure, algorithm (HS256, NOT RS256), and server-controlled kid
        String[] jwtParts = accessToken.split("\\.");
        assertThat(jwtParts).hasSize(3);

        JsonNode headerNode = objectMapper.readTree(Base64.getUrlDecoder().decode(jwtParts[0]));
        assertThat(headerNode.get("alg").asText()).isEqualTo("HS256");
        assertThat(headerNode.get("typ").asText()).isEqualTo("JWT");
        assertThat(headerNode.get("kid").asText()).isEqualTo(jwtProperties.getActiveKeyId());

        // 3. Assert exact payload claims: sub, iss, aud, iat, nbf, exp, jti, token_type, and mandatory iat_millis
        JsonNode payloadNode = objectMapper.readTree(Base64.getUrlDecoder().decode(jwtParts[1]));
        assertThat(payloadNode.get("sub").asText()).isEqualTo(staffId.toString());
        assertThat(payloadNode.get("iss").asText()).isEqualTo("homestay-pms-test");
        assertThat(payloadNode.has("aud")).isTrue();
        assertThat(payloadNode.has("iat")).isTrue();
        assertThat(payloadNode.has("nbf")).isTrue();
        assertThat(payloadNode.has("exp")).isTrue();
        assertThat(payloadNode.has("jti")).isTrue();
        assertThat(payloadNode.get("token_type").asText()).isEqualTo("access");
        assertThat(payloadNode.has("iat_millis")).isTrue();
        assertThat(payloadNode.get("iat_millis").isNumber()).isTrue();

        // 4. Assert strict absence of password hashes, roles, permissions, and unverified authorization claims
        assertThat(payloadNode.has("password")).isFalse();
        assertThat(payloadNode.has("password_hash")).isFalse();
        assertThat(payloadNode.has("passwordHash")).isFalse();
        assertThat(payloadNode.has("roles")).isFalse();
        assertThat(payloadNode.has("role")).isFalse();
        assertThat(payloadNode.has("permissions")).isFalse();
        assertThat(payloadNode.has("property_permissions")).isFalse();
        assertThat(payloadNode.has("propertyPermissions")).isFalse();
        assertThat(payloadNode.has("authorities")).isFalse();
        assertThat(payloadNode.has("scope")).isFalse();

        // Verify database audit log: Exactly one LOGIN_SUCCESS event recorded
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email, staff_user_id, user_agent FROM staff_security_audit_events WHERE email = ?",
                email.toLowerCase()
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("event_type")).isEqualTo("LOGIN_SUCCESS");
        assertThat(auditEvents.get(0).get("staff_user_id")).isEqualTo(staffId);
        assertThat(auditEvents.get(0).get("user_agent")).isEqualTo("Mozilla/5.0 StaffTestClient");
    }

    // =========================================================================
    // 2. Credential Failures & Generic 401 Responses
    // =========================================================================

    @Test
    @DisplayName("Credential Failure: Incorrect password returns HTTP 401 with generic message and no token")
    void shouldRejectIncorrectPasswordWithGeneric401() throws Exception {
        String email = "bob" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, "WrongPassword#9999");

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Verify audit log: LOGIN_FAILED recorded
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email FROM staff_security_audit_events WHERE email = ?",
                email.toLowerCase()
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("event_type")).isEqualTo("LOGIN_FAILED");

        // Verify failed login attempts incremented in database
        Integer failedAttempts = adminJdbcTemplate.queryForObject(
                "SELECT failed_login_attempts FROM staff_users WHERE id = ?",
                Integer.class,
                staffId
        );
        assertThat(failedAttempts).isEqualTo(1);
    }

    @Test
    @DisplayName("Account Enumeration Defense: Nonexistent email returns identical HTTP 401 generic message")
    void shouldRejectNonexistentEmailWithIdenticalGeneric401() throws Exception {
        String email = "ghost" + TEST_DOMAIN;
        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Verify audit log: LOGIN_FAILED_UNKNOWN_USER recorded
        List<Map<String, Object>> auditEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type, email FROM staff_security_audit_events WHERE email = ?",
                email.toLowerCase()
        );
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.get(0).get("event_type")).isEqualTo("LOGIN_FAILED_UNKNOWN_USER");
    }

    @Test
    @DisplayName("Account State: Inactive staff returns identical HTTP 401 generic message")
    void shouldRejectInactiveStaffWithGeneric401() throws Exception {
        String email = "inactive" + TEST_DOMAIN;
        createTestStaff(email, false, false, null); // Deactivated

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        MvcResult mvcResult = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(401);
        assertThat(mvcResult.getResponse().getStatus()).isNotEqualTo(423);
    }

    @Test
    @DisplayName("Lockout Defense: Locked staff returns generic HTTP 401 (never HTTP 423) without enumeration")
    void shouldRejectLockedStaffWithGeneric401() throws Exception {
        String email = "locked" + TEST_DOMAIN;
        createTestStaff(email, true, true, Instant.now().plusSeconds(900)); // Actively locked

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        MvcResult mvcResult = mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(401);
        assertThat(mvcResult.getResponse().getStatus()).isNotEqualTo(423);
    }

    // =========================================================================
    // 3. Request Validation & Malformed Input Handling
    // =========================================================================

    @Test
    @DisplayName("Validation: Empty or blank email/password returns HTTP 400 Bad Request")
    void shouldRejectBlankFieldsWith400() throws Exception {
        // Blank email
        StaffLoginRequest blankEmail = new StaffLoginRequest("", "ValidPassword#123");
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(400));

        // Blank password
        StaffLoginRequest blankPassword = new StaffLoginRequest("valid@example.com", "");
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(400));

        // Malformed email format
        StaffLoginRequest malformedEmail = new StaffLoginRequest("not-an-email", "ValidPassword#123");
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(malformedEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("Malformed JSON: Unparseable request body returns HTTP 400 Bad Request")
    void shouldRejectMalformedJsonWith400() throws Exception {
        String malformedJson = "{email: invalid_json";

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    // =========================================================================
    // 4. Rate Limiting & Account Lockout Enforcement
    // =========================================================================

    @Test
    @DisplayName("Lockout: Five consecutive failed logins trigger database account lockout")
    void shouldTriggerDatabaseLockoutAfterFiveFailedAttempts() throws Exception {
        String email = "target" + TEST_DOMAIN;
        UUID staffId = createTestStaff(email, true, false, null);

        StaffLoginRequest badRequest = new StaffLoginRequest(email, "WrongPassword#1");

        // Execute 5 consecutive failed attempts
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post(LOGIN_ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password."));
        }

        // Verify account is now locked in database
        Map<String, Object> staffRecord = adminJdbcTemplate.queryForMap(
                "SELECT is_locked, lockout_until, failed_login_attempts FROM staff_users WHERE id = ?",
                staffId
        );
        assertThat(staffRecord.get("is_locked")).isEqualTo(true);
        assertThat(staffRecord.get("failed_login_attempts")).isEqualTo(5);
        assertThat(staffRecord.get("lockout_until")).isNotNull();

        Timestamp lockoutUntil = (Timestamp) staffRecord.get("lockout_until");
        assertThat(lockoutUntil.toInstant()).isAfter(Instant.now());

        // Verify lockout audit event was written
        List<Map<String, Object>> lockoutEvents = adminJdbcTemplate.queryForList(
                "SELECT event_type FROM staff_security_audit_events WHERE email = ? AND event_type = 'ACCOUNT_LOCKED'",
                email.toLowerCase()
        );
        assertThat(lockoutEvents).hasSize(1);
    }

    // =========================================================================
    // 5. Client IP & Header Security
    // =========================================================================

    @Test
    @DisplayName("IP Spoofing Defense: Untrusted X-Forwarded-For cannot spoof the audit log IP address")
    void shouldIgnoreUntrustedXForwardedForHeader() throws Exception {
        String email = "spoof" + TEST_DOMAIN;
        createTestStaff(email, true, false, null);

        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Forwarded-For", "203.0.113.195")) // Untrusted spoof attempt
                .andExpect(status().isOk());

        // In MockMvc, remote address is 127.0.0.1 by default.
        // Because 127.0.0.1 is not configured as a trusted proxy in test properties,
        // the audit table must record 127.0.0.1, NOT 203.0.113.195!
        String recordedIp = adminJdbcTemplate.queryForObject(
                "SELECT ip_address FROM staff_security_audit_events WHERE email = ? AND event_type = 'LOGIN_SUCCESS'",
                String.class,
                email.toLowerCase()
        );
        assertThat(recordedIp).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("User-Agent Bounding: Excessively long User-Agent is safely bounded and stored without error")
    void shouldSanitizeAndBoundLongUserAgentInDatabase() throws Exception {
        String email = "longagent" + TEST_DOMAIN;
        createTestStaff(email, true, false, null);

        String hugeUserAgent = "Mozilla/5.0 " + "X".repeat(400);
        StaffLoginRequest request = new StaffLoginRequest(email, DEFAULT_PASSWORD);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", hugeUserAgent))
                .andExpect(status().isOk());

        String recordedUserAgent = adminJdbcTemplate.queryForObject(
                "SELECT user_agent FROM staff_security_audit_events WHERE email = ? AND event_type = 'LOGIN_SUCCESS'",
                String.class,
                email.toLowerCase()
        );
        assertThat(recordedUserAgent).hasSize(255);
    }
}
