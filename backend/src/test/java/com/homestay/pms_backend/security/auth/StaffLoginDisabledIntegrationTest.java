package com.homestay.pms_backend.security.auth;

import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Step 3.3C.4: Disabled Authentication Endpoint Integration Tests")
class StaffLoginDisabledIntegrationTest {

    private static final String SAMPLE_PAYLOAD = """
            {
                "email": "any@example.com",
                "password": "AnyPassword#123"
            }
            """;

    @Nested
    @IsolatedDatabaseTest
    @TestPropertySource(properties = {
            "auth.datasource.enabled=false",
            "jwt.enabled=false"
    })
    @DisplayName("Both Disabled: When auth and JWT are disabled, /api/v1/auth/login returns 404 Not Found")
    class BothDisabledTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

        @Test
        void shouldReturn404WhenBothDisabled() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SAMPLE_PAYLOAD))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @IsolatedDatabaseTest
    @TestPropertySource(properties = {
            "auth.datasource.enabled=true",
            "jwt.enabled=false"
    })
    @DisplayName("JWT Disabled: When auth is enabled but JWT is disabled, /api/v1/auth/login returns 404 Not Found")
    class JwtDisabledTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

        @Test
        void shouldReturn404WhenJwtDisabled() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SAMPLE_PAYLOAD))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @IsolatedDatabaseTest
    @TestPropertySource(properties = {
            "auth.datasource.enabled=false",
            "jwt.enabled=true"
    })
    @DisplayName("Auth DS Disabled: When JWT is enabled but auth DS is disabled, /api/v1/auth/login returns 404 Not Found")
    class AuthDsDisabledTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

        @Test
        void shouldReturn404WhenAuthDsDisabled() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SAMPLE_PAYLOAD))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isNotFound());
        }
    }
}
