package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.config.SecurityEnforcementProperties;
import com.homestay.pms_backend.dto.request.AmenityCreateRequest;
import com.homestay.pms_backend.dto.request.AmenityUpdateRequest;
import com.homestay.pms_backend.dto.request.BedTypeCreateRequest;
import com.homestay.pms_backend.dto.request.BedTypeUpdateRequest;
import com.homestay.pms_backend.dto.request.BookingSourceUpdateRequest;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.enums.AmenityCategory;
import com.homestay.pms_backend.exception.DuplicateResourceException;
import com.homestay.pms_backend.exception.ResourceNotFoundException;
import com.homestay.pms_backend.repository.AmenityRepository;
import com.homestay.pms_backend.repository.BedTypeRepository;
import com.homestay.pms_backend.repository.BookingSourceRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.security.jwt.JwtProperties;
import com.homestay.pms_backend.service.AmenityService;
import com.homestay.pms_backend.service.BedTypeService;
import com.homestay.pms_backend.service.BookingSourceService;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.5B: Global Catalog Security Integration Tests")
class GlobalCatalogSecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@test-catalog-authz.com";
    private static final String VALID_BCRYPT_HASH = "$2a$12$e80yq7KjVpEw3W6xK9qXxeQjJcM1q4RkQ9aVbY2vK8n1o2p3q4r5s";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private BookingSourceService bookingSourceService;

    @Autowired
    private AmenityService amenityService;

    @Autowired
    private BedTypeService bedTypeService;

    @Autowired
    private BookingSourceRepository bookingSourceRepository;

    @Autowired
    private AmenityRepository amenityRepository;

    @Autowired
    private BedTypeRepository bedTypeRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;

    private UUID superAdminId;
    private String superAdminToken;

    private UUID propAdminIdA;
    private String propAdminTokenA;

    private UUID receptionistIdA;
    private String receptionistTokenA;

    private UUID accountantIdA;
    private String accountantTokenA;

    private UUID viewerIdA;
    private String viewerTokenA;

    private UUID multiPropStaffId;
    private String multiPropStaffToken;

    private UUID testBookingSourceId;
    private UUID testAmenityId;
    private UUID testBedTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        SecurityContextHolder.clearContext();

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

        // 1. Seed Property A and Property B
        propertyIdA = getOrCreateProperty("Property Catalog Alpha", "PROP_CAT_ALPHA");
        propertyIdB = getOrCreateProperty("Property Catalog Beta", "PROP_CAT_BETA");

        // 2. Create Staff accounts with genuine RBAC roles
        superAdminId = createTestStaff("superadmin" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, 'SUPER_ADMIN', CURRENT_TIMESTAMP)",
                superAdminId
        );
        superAdminToken = generateTokenForStaff(superAdminId, "superadmin" + TEST_DOMAIN);

        propAdminIdA = createTestStaff("propadmin-a" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'PROPERTY_ADMIN', CURRENT_TIMESTAMP)",
                propAdminIdA, propertyIdA
        );
        propAdminTokenA = generateTokenForStaff(propAdminIdA, "propadmin-a" + TEST_DOMAIN);

        receptionistIdA = createTestStaff("receptionist-a" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'RECEPTIONIST', CURRENT_TIMESTAMP)",
                receptionistIdA, propertyIdA
        );
        receptionistTokenA = generateTokenForStaff(receptionistIdA, "receptionist-a" + TEST_DOMAIN);

        accountantIdA = createTestStaff("accountant-a" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'ACCOUNTANT', CURRENT_TIMESTAMP)",
                accountantIdA, propertyIdA
        );
        accountantTokenA = generateTokenForStaff(accountantIdA, "accountant-a" + TEST_DOMAIN);

        viewerIdA = createTestStaff("viewer-a" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'VIEWER', CURRENT_TIMESTAMP)",
                viewerIdA, propertyIdA
        );
        viewerTokenA = generateTokenForStaff(viewerIdA, "viewer-a" + TEST_DOMAIN);

        // Staff assigned to multiple properties (PROPERTY_ADMIN on A, RECEPTIONIST on B), but NOT global SUPER_ADMIN
        multiPropStaffId = createTestStaff("multiprop" + TEST_DOMAIN);
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'PROPERTY_ADMIN', CURRENT_TIMESTAMP)",
                multiPropStaffId, propertyIdA
        );
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, 'RECEPTIONIST', CURRENT_TIMESTAMP)",
                multiPropStaffId, propertyIdB
        );
        multiPropStaffToken = generateTokenForStaff(multiPropStaffId, "multiprop" + TEST_DOMAIN);

        // 3. Create isolated disposable test catalog entities
        testBookingSourceId = UUID.randomUUID();
        adminJdbcTemplate.update(
                "INSERT INTO booking_sources (id, code, name, is_active, created_at) VALUES (?, 'TEST_BS_01', 'Test Booking Channel', true, CURRENT_TIMESTAMP)",
                testBookingSourceId
        );

        testAmenityId = UUID.randomUUID();
        adminJdbcTemplate.update(
                "INSERT INTO amenities (id, code, name, category, description, is_active, created_at) VALUES (?, 'TEST_AMN_01', 'Test Fast WiFi', 'ROOM', 'High speed internet', true, CURRENT_TIMESTAMP)",
                testAmenityId
        );

        testBedTypeId = UUID.randomUUID();
        adminJdbcTemplate.update(
                "INSERT INTO bed_types (id, code, name, created_at) VALUES (?, 'TEST_BED_01', 'Test Super King', CURRENT_TIMESTAMP)",
                testBedTypeId
        );
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
                    "DELETE FROM staff_property_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_global_roles WHERE staff_user_id IN (SELECT id FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM staff_users WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "ALTER TABLE staff_security_audit_events ENABLE TRIGGER trg_staff_security_audit_events_immutable; " +
                    "DELETE FROM booking_sources WHERE code LIKE 'TEST_%'; " +
                    "DELETE FROM amenities WHERE code LIKE 'TEST_%'; " +
                    "DELETE FROM bed_types WHERE code LIKE 'TEST_%';"
            );
        }
    }

    private UUID getOrCreateProperty(String name, String code) {
        return propertyRepository.findByCode(code)
                .map(Property::getId)
                .orElseGet(() -> {
                    Property prop = new Property();
                    prop.setName(name);
                    prop.setCode(code);
                    prop.setDescription("Catalog test property");
                    prop.setAddressLine1("123 Test Street");
                    prop.setCity("Test City");
                    prop.setState("Test State");
                    prop.setCountry("India");
                    prop.setTimezone("UTC");
                    prop.setActive(true);
                    return propertyRepository.save(prop).getId();
                });
    }

    private UUID createTestStaff(String email) {
        UUID id = UUID.randomUUID();
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919876543210', true, false, 0, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                VALID_BCRYPT_HASH
        );
        return id;
    }

    private String generateTokenForStaff(UUID staffId, String email) {
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Test", "Staff", "+919876543210");
        AccessTokenDto dto = accessTokenService.generateAccessToken(staff);
        return dto.token();
    }

    private void setupSecurityContext(UUID staffId, String email) {
        AuthenticatedStaffPrincipal principal = new AuthenticatedStaffPrincipal(staffId, email, "Test", "Staff");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // 1. Global SUPER_ADMIN Success Cases (All 9 Mutation Routes)
    // =========================================================================

    @Test
    @DisplayName("SUPER_ADMIN can update booking source")
    void superAdmin_canUpdateBookingSource() throws Exception {
        BookingSourceUpdateRequest request = new BookingSourceUpdateRequest();
        request.setName("Updated Booking Channel Name");
        request.setActive(true);

        mockMvc.perform(put("/api/v1/booking-sources/" + testBookingSourceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Booking Channel Name"))
                .andExpect(jsonPath("$.code").value("TEST_BS_01"));
    }

    @Test
    @DisplayName("SUPER_ADMIN can deactivate and activate booking source")
    void superAdmin_canToggleBookingSourceActiveState() throws Exception {
        // Deactivate
        mockMvc.perform(patch("/api/v1/booking-sources/" + testBookingSourceId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        assertThat(bookingSourceRepository.findById(testBookingSourceId).orElseThrow().isActive()).isFalse();

        // Activate
        mockMvc.perform(patch("/api/v1/booking-sources/" + testBookingSourceId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        assertThat(bookingSourceRepository.findById(testBookingSourceId).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("SUPER_ADMIN can create, update, deactivate, and activate amenity")
    void superAdmin_canPerformAmenityMutations() throws Exception {
        // Create
        AmenityCreateRequest createReq = new AmenityCreateRequest();
        createReq.setCode("TEST_AMN_NEW");
        createReq.setName("New Test Amenity");
        createReq.setCategory(AmenityCategory.PROPERTY);
        createReq.setDescription("Property level amenity");

        String respStr = mockMvc.perform(post("/api/v1/amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST_AMN_NEW"))
                .andExpect(jsonPath("$.name").value("New Test Amenity"))
                .andReturn().getResponse().getContentAsString();

        UUID createdId = UUID.fromString(objectMapper.readTree(respStr).get("id").asText());

        // Update
        AmenityUpdateRequest updateReq = new AmenityUpdateRequest();
        updateReq.setName("Updated Test Amenity Name");
        updateReq.setCategory(AmenityCategory.ROOM);
        updateReq.setDescription("Updated description");

        mockMvc.perform(put("/api/v1/amenities/" + createdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Test Amenity Name"));

        // Deactivate
        mockMvc.perform(patch("/api/v1/amenities/" + createdId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        assertThat(amenityRepository.findById(createdId).orElseThrow().isActive()).isFalse();

        // Activate
        mockMvc.perform(patch("/api/v1/amenities/" + createdId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken))
                .andExpect(status().isNoContent());

        assertThat(amenityRepository.findById(createdId).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("SUPER_ADMIN can create and update bed type")
    void superAdmin_canPerformBedTypeMutations() throws Exception {
        // Create
        BedTypeCreateRequest createReq = new BedTypeCreateRequest();
        createReq.setCode("TEST_BED_NEW");
        createReq.setName("New Test Bed");

        String respStr = mockMvc.perform(post("/api/v1/bed-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("TEST_BED_NEW"))
                .andExpect(jsonPath("$.name").value("New Test Bed"))
                .andReturn().getResponse().getContentAsString();

        UUID createdId = UUID.fromString(objectMapper.readTree(respStr).get("id").asText());

        // Update
        BedTypeUpdateRequest updateReq = new BedTypeUpdateRequest();
        updateReq.setName("Updated Test Bed Name");

        mockMvc.perform(put("/api/v1/bed-types/" + createdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Test Bed Name"));
    }

    // =========================================================================
    // 2-6. Non-SUPER_ADMIN Role Denial (HTTP 403) Across All 9 Mutation Routes
    // =========================================================================

    @Test
    @DisplayName("PROPERTY_ADMIN is denied with 403 on all 9 catalog mutation endpoints")
    void propertyAdmin_deniedOnAllMutations() throws Exception {
        verifyAllMutationsDeniedWithForbidden(propAdminTokenA);
    }

    @Test
    @DisplayName("RECEPTIONIST is denied with 403 on all 9 catalog mutation endpoints")
    void receptionist_deniedOnAllMutations() throws Exception {
        verifyAllMutationsDeniedWithForbidden(receptionistTokenA);
    }

    @Test
    @DisplayName("ACCOUNTANT is denied with 403 on all 9 catalog mutation endpoints")
    void accountant_deniedOnAllMutations() throws Exception {
        verifyAllMutationsDeniedWithForbidden(accountantTokenA);
    }

    @Test
    @DisplayName("VIEWER is denied with 403 on all 9 catalog mutation endpoints")
    void viewer_deniedOnAllMutations() throws Exception {
        verifyAllMutationsDeniedWithForbidden(viewerTokenA);
    }

    @Test
    @DisplayName("Staff with access to multiple properties but lacking global SUPER_ADMIN is denied with 403 on all 9 mutations")
    void multiPropertyStaff_deniedOnAllMutations() throws Exception {
        verifyAllMutationsDeniedWithForbidden(multiPropStaffToken);
    }

    private void verifyAllMutationsDeniedWithForbidden(String token) throws Exception {
        // 1. PUT booking source
        BookingSourceUpdateRequest bsReq = new BookingSourceUpdateRequest();
        bsReq.setName("Hacked Name");
        mockMvc.perform(put("/api/v1/booking-sources/" + testBookingSourceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bsReq)))
                .andExpect(status().isForbidden());

        // 2. PATCH booking source deactivate
        mockMvc.perform(patch("/api/v1/booking-sources/" + testBookingSourceId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        // 3. PATCH booking source activate
        mockMvc.perform(patch("/api/v1/booking-sources/" + testBookingSourceId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        // 4. POST amenity
        AmenityCreateRequest amnCreate = new AmenityCreateRequest();
        amnCreate.setCode("TEST_AMN_UNAUTH");
        amnCreate.setName("Unauthorized Amenity");
        amnCreate.setCategory(AmenityCategory.ROOM);
        mockMvc.perform(post("/api/v1/amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(amnCreate)))
                .andExpect(status().isForbidden());

        // 5. PUT amenity
        AmenityUpdateRequest amnUpdate = new AmenityUpdateRequest();
        amnUpdate.setName("Hacked Amenity");
        mockMvc.perform(put("/api/v1/amenities/" + testAmenityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(amnUpdate)))
                .andExpect(status().isForbidden());

        // 6. PATCH amenity deactivate
        mockMvc.perform(patch("/api/v1/amenities/" + testAmenityId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        // 7. PATCH amenity activate
        mockMvc.perform(patch("/api/v1/amenities/" + testAmenityId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        // 8. POST bed type
        BedTypeCreateRequest bedCreate = new BedTypeCreateRequest();
        bedCreate.setCode("TEST_BED_UNAUTH");
        bedCreate.setName("Unauthorized Bed");
        mockMvc.perform(post("/api/v1/bed-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedCreate)))
                .andExpect(status().isForbidden());

        // 9. PUT bed type
        BedTypeUpdateRequest bedUpdate = new BedTypeUpdateRequest();
        bedUpdate.setName("Hacked Bed");
        mockMvc.perform(put("/api/v1/bed-types/" + testBedTypeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedUpdate)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 7. Read Compatibility Across All 5 Roles (HTTP 200)
    // =========================================================================

    @Test
    @DisplayName("All 5 staff roles have read access to all 3 catalogs")
    void allStaffRoles_canReadCatalogs() throws Exception {
        List<String> tokens = List.of(superAdminToken, propAdminTokenA, receptionistTokenA, accountantTokenA, viewerTokenA);

        for (String token : tokens) {
            // Booking sources
            mockMvc.perform(get("/api/v1/booking-sources")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(empty())));

            mockMvc.perform(get("/api/v1/booking-sources/" + testBookingSourceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testBookingSourceId.toString()));

            // Amenities
            mockMvc.perform(get("/api/v1/amenities")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(empty())));

            mockMvc.perform(get("/api/v1/amenities/" + testAmenityId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testAmenityId.toString()));

            // Bed types
            mockMvc.perform(get("/api/v1/bed-types")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(empty())));

            mockMvc.perform(get("/api/v1/bed-types/" + testBedTypeId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testBedTypeId.toString()));
        }
    }

    // =========================================================================
    // 8-10. Authentication Rejections (HTTP 401)
    // =========================================================================

    @Test
    @DisplayName("Missing JWT receives 401 Unauthorized across catalog endpoints")
    void missingJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/booking-sources"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/amenities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/v1/bed-types/" + testBedTypeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Invalid or tampered JWT receives 401 Unauthorized")
    void invalidJwt_returns401() throws Exception {
        String tamperedToken = superAdminToken + "invalid";

        mockMvc.perform(get("/api/v1/amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/booking-sources/" + testBookingSourceId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Expired JWT receives 401 Unauthorized across catalog endpoints")
    void expiredJwt_returns401() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        SecretKey key = jwtProperties.getActiveSecretKey();

        String expiredToken = Jwts.builder()
                .header().type("JWT").keyId(jwtProperties.getActiveKeyId()).and()
                .subject(superAdminId.toString())
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

        mockMvc.perform(get("/api/v1/booking-sources")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/v1/bed-types/" + testBedTypeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 11. Dynamic Role Revocation
    // =========================================================================

    @Test
    @DisplayName("Dynamic global-role revocation immediately blocks subsequent mutations with 403")
    void dynamicRoleRevocation_blocksImmediately() throws Exception {
        // Step 1: Initial mutation as SUPER_ADMIN succeeds
        BookingSourceUpdateRequest updateReq = new BookingSourceUpdateRequest();
        updateReq.setName("Valid Name Before Revocation");

        mockMvc.perform(put("/api/v1/booking-sources/" + testBookingSourceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());

        // Step 2: Revoke SUPER_ADMIN role from database
        adminJdbcTemplate.update(
                "DELETE FROM staff_global_roles WHERE staff_user_id = ?",
                superAdminId
        );

        // Step 3: Immediate subsequent mutation with same token fails with 403
        updateReq.setName("Attempt After Revocation");
        mockMvc.perform(put("/api/v1/booking-sources/" + testBookingSourceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 12-13. Anti-Enumeration & Resource Existence
    // =========================================================================

    @Test
    @DisplayName("Non-SUPER_ADMIN mutation with nonexistent UUID returns 403, not 404 (anti-enumeration)")
    void nonSuperAdmin_nonexistentUuid_returns403() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        // Booking source
        BookingSourceUpdateRequest bsReq = new BookingSourceUpdateRequest();
        bsReq.setName("Probe");
        mockMvc.perform(put("/api/v1/booking-sources/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bsReq)))
                .andExpect(status().isForbidden());

        // Amenity
        AmenityUpdateRequest amnReq = new AmenityUpdateRequest();
        amnReq.setName("Probe");
        mockMvc.perform(put("/api/v1/amenities/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(amnReq)))
                .andExpect(status().isForbidden());

        // Bed type
        BedTypeUpdateRequest bedReq = new BedTypeUpdateRequest();
        bedReq.setName("Probe");
        mockMvc.perform(put("/api/v1/bed-types/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SUPER_ADMIN mutation with nonexistent UUID returns 404 Not Found")
    void superAdmin_nonexistentUuid_returns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        // Booking source
        BookingSourceUpdateRequest bsReq = new BookingSourceUpdateRequest();
        bsReq.setName("Update Nonexistent");
        mockMvc.perform(put("/api/v1/booking-sources/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bsReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Booking source not found with id: " + nonexistentId)));

        // Amenity
        AmenityUpdateRequest amnReq = new AmenityUpdateRequest();
        amnReq.setName("Update Nonexistent");
        mockMvc.perform(put("/api/v1/amenities/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(amnReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Amenity not found with id: " + nonexistentId)));

        // Bed type
        BedTypeUpdateRequest bedReq = new BedTypeUpdateRequest();
        bedReq.setName("Update Nonexistent");
        mockMvc.perform(put("/api/v1/bed-types/" + nonexistentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bedReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Bed type not found with id: " + nonexistentId)));
    }

    // =========================================================================
    // 14. Direct Service Invocation Protection
    // =========================================================================

    @Test
    @DisplayName("Direct service calls without SUPER_ADMIN in SecurityContext throw AccessDeniedException")
    void directServiceCall_withoutSuperAdmin_throwsAccessDenied() {
        // Set up SecurityContext with PROPERTY_ADMIN
        setupSecurityContext(propAdminIdA, "propadmin-a" + TEST_DOMAIN);

        try {
            // BookingSourceService
            BookingSourceUpdateRequest bsReq = new BookingSourceUpdateRequest();
            bsReq.setName("Direct Service Hack");
            assertThatThrownBy(() -> bookingSourceService.updateBookingSource(testBookingSourceId, bsReq))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> bookingSourceService.deactivateBookingSource(testBookingSourceId))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> bookingSourceService.activateBookingSource(testBookingSourceId))
                    .isInstanceOf(AccessDeniedException.class);

            // AmenityService
            AmenityCreateRequest amnCreate = new AmenityCreateRequest();
            amnCreate.setCode("TEST_AMN_DIRECT");
            amnCreate.setName("Direct Amenity");
            assertThatThrownBy(() -> amenityService.createAmenity(amnCreate))
                    .isInstanceOf(AccessDeniedException.class);

            AmenityUpdateRequest amnUpdate = new AmenityUpdateRequest();
            amnUpdate.setName("Direct Amenity");
            assertThatThrownBy(() -> amenityService.updateAmenity(testAmenityId, amnUpdate))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> amenityService.deactivateAmenity(testAmenityId))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> amenityService.activateAmenity(testAmenityId))
                    .isInstanceOf(AccessDeniedException.class);

            // BedTypeService
            BedTypeCreateRequest bedCreate = new BedTypeCreateRequest();
            bedCreate.setCode("TEST_BED_DIRECT");
            bedCreate.setName("Direct Bed");
            assertThatThrownBy(() -> bedTypeService.createBedType(bedCreate))
                    .isInstanceOf(AccessDeniedException.class);

            BedTypeUpdateRequest bedUpdate = new BedTypeUpdateRequest();
            bedUpdate.setName("Direct Bed");
            assertThatThrownBy(() -> bedTypeService.updateBedType(testBedTypeId, bedUpdate))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // 15. Business Validation Retained Under Authorization
    // =========================================================================

    @Test
    @DisplayName("Authorized service calls retain standard business validations (e.g., duplicate code rejection)")
    void authorizedServiceCalls_retainBusinessValidations() throws Exception {
        // Duplicate amenity code rejection (HTTP 409 Conflict)
        AmenityCreateRequest duplicateReq = new AmenityCreateRequest();
        duplicateReq.setCode("TEST_AMN_01"); // Already exists
        duplicateReq.setName("Duplicate Amenity");
        duplicateReq.setCategory(AmenityCategory.PROPERTY);

        mockMvc.perform(post("/api/v1/amenities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));

        // Duplicate bed type code rejection (HTTP 409 Conflict)
        BedTypeCreateRequest dupBedReq = new BedTypeCreateRequest();
        dupBedReq.setCode("TEST_BED_01"); // Already exists
        dupBedReq.setName("Duplicate Bed Type");

        mockMvc.perform(post("/api/v1/bed-types")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupBedReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    // =========================================================================
    // 16. Development Enforcement-Disabled Compatibility
    // =========================================================================

    @Test
    @DisplayName("When enforcement is disabled, guard permits mutations without authentication")
    void whenEnforcementDisabled_guardPermitsMutations() {
        SecurityEnforcementProperties disabledProps = new SecurityEnforcementProperties();
        disabledProps.setEnabled(false);

        GlobalCatalogAuthorizationGuard disabledGuard = new GlobalCatalogAuthorizationGuard(
                authorizationService,
                disabledProps
        );

        // Must not throw AccessDeniedException even when SecurityContext is empty
        SecurityContextHolder.clearContext();
        disabledGuard.requireGlobalSuperAdminForMutation();
        assertThat(disabledGuard.isEnforcementEnabled()).isFalse();
    }

    @Autowired
    private StaffAuthorizationService authorizationService;

    // =========================================================================
    // 17. Unauthorized Mutations Leave Database Unchanged
    // =========================================================================

    @Test
    @DisplayName("Failed unauthorized mutations leave catalog records strictly unchanged in database")
    void unauthorizedMutations_leaveDatabaseUnchanged() throws Exception {
        String originalName = bookingSourceRepository.findById(testBookingSourceId).orElseThrow().getName();

        BookingSourceUpdateRequest updateReq = new BookingSourceUpdateRequest();
        updateReq.setName("Hacked Channel Name");

        mockMvc.perform(put("/api/v1/booking-sources/" + testBookingSourceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // Verify database value is unmodified
        assertThat(bookingSourceRepository.findById(testBookingSourceId).orElseThrow().getName())
                .isEqualTo(originalName);
    }
}
