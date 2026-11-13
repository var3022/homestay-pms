package com.homestay.pms_backend.exception;

import com.homestay.pms_backend.security.StaffAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 3.3C.4: GlobalExceptionHandler Compatibility Unit Tests")
class GlobalExceptionHandlerUnitTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("StaffAuthenticationException returns HTTP 401 with generic message, no-store headers, and never 423")
    void shouldHandleStaffAuthenticationException() {
        StaffAuthenticationException ex = new StaffAuthenticationException("Account is locked");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleStaffAuthentication(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode().value()).isNotEqualTo(423);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("Unauthorized");
        assertThat(response.getBody().message()).isEqualTo("Invalid email or password.");
        assertThat(response.getBody().message()).doesNotContain("locked");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException returns HTTP 400 with generic malformed message and no-store headers")
    void shouldHandleHttpMessageNotReadableException() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('{' (code 123))",
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Malformed or unreadable request body");
        assertThat(response.getBody().message()).doesNotContain("JSON parse error");
    }

    @Test
    @DisplayName("DataAccessException returns HTTP 500 with sanitized message hiding SQL and table names")
    void shouldHandleDataAccessException() {
        DataAccessResourceFailureException ex = new DataAccessResourceFailureException(
                "SELECT * FROM staff_users WHERE email = 'secret' failed: Connection refused"
        );

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleDataAccessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().message()).isEqualTo("An internal database error occurred. Please try again later.");
        assertThat(response.getBody().message()).doesNotContain("staff_users");
        assertThat(response.getBody().message()).doesNotContain("SELECT");
    }

    @Test
    @DisplayName("ResourceNotFoundException preserves backwards compatibility returning HTTP 404")
    void shouldPreserveResourceNotFoundCompatibility() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Reservation not found with ID: 42");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleResourceNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("Resource Not Found");
        assertThat(response.getBody().message()).isEqualTo("Reservation not found with ID: 42");
    }

    @Test
    @DisplayName("DuplicateResourceException preserves backwards compatibility returning HTTP 409 Conflict")
    void shouldPreserveDuplicateResourceCompatibility() {
        DuplicateResourceException ex = new DuplicateResourceException("Room number 101 already exists in this property");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleDuplicateResource(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Duplicate Resource");
        assertThat(response.getBody().message()).isEqualTo("Room number 101 already exists in this property");
    }

    @Test
    @DisplayName("BusinessValidationException preserves backwards compatibility returning HTTP 400")
    void shouldPreserveBusinessValidationCompatibility() {
        BusinessValidationException ex = new BusinessValidationException("Check-out date must be after check-in date");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleBusinessValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Business Validation Failed");
        assertThat(response.getBody().message()).isEqualTo("Check-out date must be after check-in date");
    }
}
