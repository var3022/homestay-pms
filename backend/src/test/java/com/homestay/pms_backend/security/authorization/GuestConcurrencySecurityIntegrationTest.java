package com.homestay.pms_backend.security.authorization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.homestay.pms_backend.dto.request.GuestUpdateRequest;
import com.homestay.pms_backend.entity.Guest;
import com.homestay.pms_backend.entity.Property;
import com.homestay.pms_backend.enums.GuestAssociationSource;
import com.homestay.pms_backend.repository.GuestPropertyAssociationRepository;
import com.homestay.pms_backend.repository.GuestRepository;
import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import com.homestay.pms_backend.service.GuestPropertyAssociationService;
import com.homestay.pms_backend.service.GuestService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=true",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=true"
})
@DisplayName("PMS Phase 2 — Step 3.4.3.3B.5: Guest Concurrency and Isolation Security Integration Tests")
class GuestConcurrencySecurityIntegrationTest {

    private static final String TEST_DOMAIN = "@guest-concur-test.com";
    private static final String VALID_BCRYPT_HASH = "$2a$10$wT0l8kPqSsqGzC.q0Qe11eZ7t6jPqXn3yEwK6Tq5Z7hC0d1z8Kz6W";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private GuestRepository guestRepository;

    @Autowired
    private GuestPropertyAssociationRepository associationRepository;

    @Autowired
    private GuestPropertyAssociationService associationService;

    @Autowired
    private GuestService guestService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = createObjectMapper();
    private JdbcTemplate adminJdbcTemplate;

    private UUID propertyIdA;
    private UUID propertyIdB;
    private UUID concurrentGuestId;

    private UUID staffAId;
    private String propAdminTokenA;
    private String superAdminToken;

    private ExecutorService executor;

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule dateModule = new SimpleModule();
        dateModule.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        dateModule.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return LocalDate.parse(p.getText());
            }
        });
        dateModule.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        dateModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getText());
            }
        });
        mapper.registerModule(dateModule);
        return mapper;
    }

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
        adminJdbcTemplate = new JdbcTemplate(adminDs);

        cleanupTestData();

        // 1. Seed Properties
        propertyIdA = getOrCreateProperty("Property Alpha Concur", "PROP_A_CONCUR");
        propertyIdB = getOrCreateProperty("Property Beta Concur", "PROP_B_CONCUR");

        // 2. Seed Single-Property Guest
        concurrentGuestId = createGuestInDb("Original Guest Name", "concur-guest" + TEST_DOMAIN, "+919876541111", "P99887766");
        associationService.createRegistrationAssociation(concurrentGuestId, propertyIdA);

        // 3. Seed Tokens
        superAdminToken = createStaffToken("superadmin-c" + TEST_DOMAIN, StaffRole.SUPER_ADMIN, null);

        staffAId = createTestStaff("propadmin-ca" + TEST_DOMAIN, true, false);
        assignStaffPropertyRole(staffAId, propertyIdA, StaffRole.PROPERTY_ADMIN);
        propAdminTokenA = generateTokenForStaff(staffAId, "propadmin-ca" + TEST_DOMAIN);

        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
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
                    "DELETE FROM guest_property_associations WHERE guest_id IN (SELECT id FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'); " +
                    "DELETE FROM guests WHERE email LIKE '%" + TEST_DOMAIN + "'; " +
                    "DELETE FROM properties WHERE code IN ('PROP_A_CONCUR', 'PROP_B_CONCUR');"
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
                    prop.setDescription("Concurrency test property");
                    prop.setAddressLine1("100 Concur St");
                    prop.setCity("Concur City");
                    prop.setState("Concur State");
                    prop.setCountry("India");
                    prop.setTimezone("Asia/Kolkata");
                    prop.setActive(true);
                    return propertyRepository.save(prop).getId();
                });
    }

    private UUID createGuestInDb(String name, String email, String phone, String idNumber) {
        Guest g = new Guest();
        g.setName(name);
        g.setEmail(email);
        g.setPhone(phone);
        g.setIdNumber(idNumber);
        g.setActive(true);
        return guestRepository.save(g).getId();
    }

    private UUID createTestStaff(String email, boolean isActive, boolean isLocked) {
        UUID id = UUID.randomUUID();
        adminJdbcTemplate.update(
                """
                INSERT INTO staff_users (
                    id, email, password_hash, first_name, last_name, phone,
                    is_active, is_locked, failed_login_attempts, lockout_until,
                    last_login_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'Test', 'Staff', '+919876543210', ?, ?, 0, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '5 seconds', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                email.trim().toLowerCase(),
                VALID_BCRYPT_HASH,
                isActive,
                isLocked
        );
        return id;
    }

    private void assignStaffPropertyRole(UUID staffId, UUID propertyId, StaffRole role) {
        adminJdbcTemplate.update(
                "INSERT INTO staff_property_roles (staff_user_id, property_id, role_code, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                staffId,
                propertyId,
                role.getCode()
        );
    }

    private String generateTokenForStaff(UUID staffId, String email) {
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, email, "Test", "Staff", "+919876543210");
        AccessTokenDto dto = accessTokenService.generateAccessToken(staff);
        return dto.token();
    }

    private String createStaffToken(String email, StaffRole role, UUID propertyId) {
        UUID staffId = createTestStaff(email, true, false);
        if (role == StaffRole.SUPER_ADMIN) {
            adminJdbcTemplate.update(
                    "INSERT INTO staff_global_roles (staff_user_id, role_code, assigned_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    staffId,
                    role.getCode()
            );
        } else if (role != null && propertyId != null) {
            assignStaffPropertyRole(staffId, propertyId, role);
        }
        return generateTokenForStaff(staffId, email);
    }

    @Test
    @DisplayName("Concurrency 1: Scoped update blocked behind association commit is rejected with 403 on shared guest")
    void test01_concurrentUpdateBlockedWhenAssociationCommittedFirst() throws Exception {
        TransactionTemplate txTemplateB = new TransactionTemplate(transactionManager);
        txTemplateB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txBHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txAAttemptedLatch = new CountDownLatch(1);

        // Transaction B runs in background thread: acquires pessimistic lock, waits for Tx A to block, adds property B, commits
        Future<?> futureB = executor.submit(() -> {
            txTemplateB.execute(status -> {
                // Acquire pessimistic write lock on guest row
                guestRepository.findByIdForUpdate(concurrentGuestId).orElseThrow();

                // Signal that Tx B now holds the row lock
                txBHoldingLockLatch.countDown();

                // Wait until Tx A has initiated its update and blocked on the lock
                try {
                    boolean signaled = txAAttemptedLatch.await(3, TimeUnit.SECONDS);
                    assertThat(signaled).isTrue();
                    // Additional sleep to ensure Tx A's thread is actively waiting in PostgreSQL kernel
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Add association for Property B within Tx B
                associationRepository.insertAssociationIdempotent(
                        concurrentGuestId, propertyIdB, GuestAssociationSource.HISTORICAL_BACKFILL.name());

                return null; // Tx B commits here, releasing row lock
            });
        });

        // Wait until Tx B has confirmed lock acquisition
        assertThat(txBHoldingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Transaction A runs via MockMvc in a separate thread: attempts to update guest name
        GuestUpdateRequest updateRequest = new GuestUpdateRequest();
        updateRequest.setName("Attempted Stale Scoped Update");

        Future<MvcResult> futureA = executor.submit(() -> {
            // Signal to Tx B that Tx A is initiating and will wait on the row lock
            txAAttemptedLatch.countDown();

            return mockMvc.perform(put("/api/v1/guests/" + concurrentGuestId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andReturn();
        });

        // Wait for both threads to finish
        futureB.get(10, TimeUnit.SECONDS);
        MvcResult resultA = futureA.get(10, TimeUnit.SECONDS);

        // Invariant Verification:
        // 1. Transaction A must be REJECTED with 403 Forbidden because guest became shared before A's update
        assertThat(resultA.getResponse().getStatus()).isEqualTo(403);

        // 2. The guest's name must NOT have been updated
        Guest finalGuest = guestRepository.findById(concurrentGuestId).orElseThrow();
        assertThat(finalGuest.getName()).isEqualTo("Original Guest Name");

        // 3. The guest is now associated with both properties
        List<UUID> assocProps = associationRepository.findPropertyIdsByGuestId(concurrentGuestId);
        assertThat(assocProps).containsExactlyInAnyOrder(propertyIdA, propertyIdB);
    }

    @Test
    @DisplayName("Concurrency 2: Scoped update serialized before association succeeds and commits before second association")
    void test02_concurrentUpdateSucceedsWhenSerializedBeforeAssociation() throws Exception {
        TransactionTemplate txTemplateA = new TransactionTemplate(transactionManager);
        txTemplateA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionTemplate txTemplateB = new TransactionTemplate(transactionManager);
        txTemplateB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txAHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txBAttemptedLatch = new CountDownLatch(1);

        // Transaction A acquires row lock first, updates guest name, waits for B to block, then commits
        Future<?> futureA = executor.submit(() -> {
            txTemplateA.execute(status -> {
                Guest guest = guestRepository.findByIdForUpdate(concurrentGuestId).orElseThrow();

                // Signal that Tx A holds the lock
                txAHoldingLockLatch.countDown();

                try {
                    boolean signaled = txBAttemptedLatch.await(3, TimeUnit.SECONDS);
                    assertThat(signaled).isTrue();
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                guest.setName("Successfully Updated Under Lock");
                guestRepository.saveAndFlush(guest);
                return null; // Tx A commits
            });
        });

        // Wait until Tx A holds the lock
        assertThat(txAHoldingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Transaction B attempts to attach association for Property B and blocks behind Tx A
        Future<?> futureB = executor.submit(() -> {
            txBAttemptedLatch.countDown();

            txTemplateB.execute(status -> {
                // Must acquire pessimistic lock on guest
                guestRepository.findByIdForUpdate(concurrentGuestId).orElseThrow();
                associationRepository.insertAssociationIdempotent(
                        concurrentGuestId, propertyIdB, GuestAssociationSource.HISTORICAL_BACKFILL.name());
                return null; // Tx B commits
            });
        });

        // Wait for both transactions
        futureA.get(10, TimeUnit.SECONDS);
        futureB.get(10, TimeUnit.SECONDS);

        // Verification:
        // 1. Guest name was updated safely while single-property
        Guest updatedGuest = guestRepository.findById(concurrentGuestId).orElseThrow();
        assertThat(updatedGuest.getName()).isEqualTo("Successfully Updated Under Lock");

        // 2. Both associations exist
        List<UUID> assocProps = associationRepository.findPropertyIdsByGuestId(concurrentGuestId);
        assertThat(assocProps).containsExactlyInAnyOrder(propertyIdA, propertyIdB);

        // 3. Subsequent update by scoped staff is now rejected (403) because guest is shared
        GuestUpdateRequest secondRequest = new GuestUpdateRequest();
        secondRequest.setName("Subsequent Blocked Update");

        mockMvc.perform(put("/api/v1/guests/" + concurrentGuestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + propAdminTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status -> assertThat(status.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    @DisplayName("Concurrency 3: Service-level concurrent update and association serialization throws AccessDeniedException")
    void test03_serviceLevelConcurrentUpdateThrowsAccessDenied() throws Exception {
        TransactionTemplate txTemplateB = new TransactionTemplate(transactionManager);
        txTemplateB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txBHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txAAttemptedLatch = new CountDownLatch(1);

        // Thread B holds lock, attaches association, commits
        Future<?> futureB = executor.submit(() -> {
            txTemplateB.execute(status -> {
                guestRepository.findByIdForUpdate(concurrentGuestId).orElseThrow();
                txBHoldingLockLatch.countDown();

                try {
                    txAAttemptedLatch.await(3, TimeUnit.SECONDS);
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                associationRepository.insertAssociationIdempotent(
                        concurrentGuestId, propertyIdB, GuestAssociationSource.HISTORICAL_BACKFILL.name());
                return null;
            });
        });

        assertThat(txBHoldingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A runs guestService.updateGuest with authenticated staff context
        Future<Exception> futureA = executor.submit(() -> {
            try {
                // Setup authenticated staff principal for Thread A
                com.homestay.pms_backend.security.AuthenticatedStaffPrincipal principal =
                        new com.homestay.pms_backend.security.AuthenticatedStaffPrincipal(
                                staffAId, "propadmin-ca" + TEST_DOMAIN, "Test", "Staff"
                        );
                SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, List.of())
                );

                txAAttemptedLatch.countDown();

                GuestUpdateRequest req = new GuestUpdateRequest();
                req.setName("Service Level Update");
                guestService.updateGuest(concurrentGuestId, req);
                return null;
            } catch (Exception e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        futureB.get(10, TimeUnit.SECONDS);
        Exception exceptionA = futureA.get(10, TimeUnit.SECONDS);

        // Thread A must throw AccessDeniedException
        assertThat(exceptionA).isNotNull();
        assertThat(exceptionA).isInstanceOf(AccessDeniedException.class);
        assertThat(exceptionA.getMessage()).contains("Shared multi-property guest identity cannot be modified by property-scoped staff");

        // Guest name remains untouched
        Guest guest = guestRepository.findById(concurrentGuestId).orElseThrow();
        assertThat(guest.getName()).isEqualTo("Original Guest Name");
    }

    @Test
    @DisplayName("Concurrency 4: SUPER_ADMIN concurrent update succeeds even when guest becomes multi-property")
    void test04_superAdminConcurrentUpdateAllowedEvenWhenMultiProperty() throws Exception {
        TransactionTemplate txTemplateB = new TransactionTemplate(transactionManager);
        txTemplateB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CountDownLatch txBHoldingLockLatch = new CountDownLatch(1);
        CountDownLatch txAAttemptedLatch = new CountDownLatch(1);

        // Thread B attaches Property B
        Future<?> futureB = executor.submit(() -> {
            txTemplateB.execute(status -> {
                guestRepository.findByIdForUpdate(concurrentGuestId).orElseThrow();
                txBHoldingLockLatch.countDown();

                try {
                    txAAttemptedLatch.await(3, TimeUnit.SECONDS);
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                associationRepository.insertAssociationIdempotent(
                        concurrentGuestId, propertyIdB, GuestAssociationSource.HISTORICAL_BACKFILL.name());
                return null;
            });
        });

        assertThat(txBHoldingLockLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A is SUPER_ADMIN
        GuestUpdateRequest request = new GuestUpdateRequest();
        request.setName("Super Admin Concurrent Update");

        Future<MvcResult> futureA = executor.submit(() -> {
            txAAttemptedLatch.countDown();

            return mockMvc.perform(put("/api/v1/guests/" + concurrentGuestId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + superAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn();
        });

        futureB.get(10, TimeUnit.SECONDS);
        MvcResult resultA = futureA.get(10, TimeUnit.SECONDS);

        // SUPER_ADMIN update succeeds with 200 OK
        assertThat(resultA.getResponse().getStatus()).isEqualTo(200);

        Guest updated = guestRepository.findById(concurrentGuestId).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Super Admin Concurrent Update");
    }
}
