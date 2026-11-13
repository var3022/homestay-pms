package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.jwt.enforcement.enabled=false",
        "TEST_AUTH_JWT_ENFORCEMENT_ENABLED=false"
})
@DisplayName("Step 3.4.2: Disabled JWT Enforcement Compatibility Integration Tests")
class DisabledJwtEnforcementIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Test 17: Disabled enforcement preserves unauthenticated access to /api/** for local dev/testing")
    void shouldAllowUnauthenticatedAccessWhenEnforcementDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/properties"))
                .andExpect(status().isOk());
    }
}
