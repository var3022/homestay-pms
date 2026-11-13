package com.homestay.pms_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homestay.pms_backend.dto.request.StaffLoginRequest;
import com.homestay.pms_backend.dto.response.StaffLoginResponse;
import com.homestay.pms_backend.exception.GlobalExceptionHandler;
import com.homestay.pms_backend.security.AuthenticatedStaff;
import com.homestay.pms_backend.security.StaffAuthenticationException;
import com.homestay.pms_backend.security.StaffAuthenticationService;
import com.homestay.pms_backend.security.auth.ClientContext;
import com.homestay.pms_backend.security.auth.ClientContextExtractor;
import com.homestay.pms_backend.security.jwt.AccessTokenDto;
import com.homestay.pms_backend.security.jwt.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private StaffAuthenticationService staffAuthenticationService;

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private ClientContextExtractor clientContextExtractor;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        StaffAuthController controller = new StaffAuthController(
                staffAuthenticationService,
                accessTokenService,
                clientContextExtractor
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Unit: Successful orchestration calls auth service then token service and returns 200")
    void shouldOrchestrateSuccessfulLogin() throws Exception {
        UUID staffId = UUID.randomUUID();
        AuthenticatedStaff staff = new AuthenticatedStaff(staffId, "alice@example.com", "Alice", "Admin", "+919876543210");
        AccessTokenDto tokenDto = new AccessTokenDto("mock.jwt.token", Instant.now().plusSeconds(900), "mock-jti", "access");

        when(clientContextExtractor.extract(any())).thenReturn(new ClientContext("192.168.1.100", "Mozilla/5.0 UnitTester"));
        when(staffAuthenticationService.authenticate(eq("alice@example.com"), eq("SecretPassword#1"), eq("192.168.1.100"), eq("Mozilla/5.0 UnitTester")))
                .thenReturn(staff);
        when(accessTokenService.generateAccessToken(staff)).thenReturn(tokenDto);

        StaffLoginRequest request = new StaffLoginRequest("alice@example.com", "SecretPassword#1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.staff.id").value(staffId.toString()))
                .andExpect(jsonPath("$.staff.email").value("alice@example.com"));

        verify(staffAuthenticationService).authenticate("alice@example.com", "SecretPassword#1", "192.168.1.100", "Mozilla/5.0 UnitTester");
        verify(accessTokenService).generateAccessToken(staff);
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
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

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
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An internal database error occurred. Please try again later."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database connection closed"))));

        verifyNoInteractions(accessTokenService);
    }
}
