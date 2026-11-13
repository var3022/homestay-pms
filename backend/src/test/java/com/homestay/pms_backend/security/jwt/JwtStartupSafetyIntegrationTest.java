package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 3.3C.3: JWT Startup Safety & Configuration Validation Tests")
class JwtStartupSafetyIntegrationTest {

    // =========================================================================
    // 1. Fail-Closed Configuration Hygiene Tests
    // =========================================================================

    @Test
    @DisplayName("Configuration Hygiene: Fails closed when jwt.enabled=true but no keys are configured")
    void shouldFailClosedWhenKeysMissing() {
        JwtProperties props = new JwtProperties();
        props.setEnabled(true);
        props.setIssuer("homestay-pms");
        props.setAudience("homestay-pms-client");
        props.setKeys(new HashMap<>());

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.keys map must contain at least one signing key");
    }

    @Test
    @DisplayName("Configuration Hygiene: Fails closed when active key ID is not found in keys map")
    void shouldFailClosedWhenActiveKeyMissingFromMap() {
        JwtProperties props = new JwtProperties();
        props.setEnabled(true);
        props.setActiveKeyId("k_active");
        Map<String, String> keys = new HashMap<>();
        keys.put("k_other", "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        props.setKeys(keys);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain active key ID 'k_active'");
    }

    @Test
    @DisplayName("Security Violation: Fails closed when secret key material is under 256 bits (< 32 bytes)")
    void shouldFailClosedWhenKeyMaterialTooShort() {
        JwtProperties props = new JwtProperties();
        props.setEnabled(true);
        props.setActiveKeyId("k1");

        // 16 bytes = 128 bits (insufficient entropy for HS256)
        byte[] shortBytes = new byte[16];
        String shortBase64 = Base64.getEncoder().encodeToString(shortBytes);

        Map<String, String> keys = new HashMap<>();
        keys.put("k1", shortBase64);
        props.setKeys(keys);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("below the minimum required 256 bits");
    }

    @Test
    @DisplayName("Configuration Hygiene: Fails closed when secret key is not valid Base64")
    void shouldFailClosedWhenSecretNotValidBase64() {
        JwtProperties props = new JwtProperties();
        props.setEnabled(true);
        props.setActiveKeyId("k1");

        Map<String, String> keys = new HashMap<>();
        keys.put("k1", "not_valid_base_64_!@#$%^&*()");
        props.setKeys(keys);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid Base64 secret");
    }

    @Test
    @DisplayName("Startup Safety: Does NOT validate or fail when jwt.enabled=false")
    void shouldNotValidateWhenDisabled() {
        JwtProperties props = new JwtProperties();
        props.setEnabled(false);
        props.setKeys(new HashMap<>()); // completely empty
        props.setIssuer(null);
        props.setAudience(null);

        // Must succeed without throwing
        props.validate();
        assertThat(props.isEnabled()).isFalse();
    }
}
