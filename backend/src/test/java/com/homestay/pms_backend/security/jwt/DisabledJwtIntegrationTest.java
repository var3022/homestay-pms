package com.homestay.pms_backend.security.jwt;

import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "jwt.enabled=false",
        "TEST_JWT_ENABLED=false"
})
@DisplayName("Disabled JWT Context: Context loads cleanly without JWT beans when jwt.enabled=false")
class DisabledJwtIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PropertyRepository propertyRepository;

    @Test
    @DisplayName("Normal PMS context operates without JWT service when disabled")
    void shouldOperateNormalPmsWhenJwtDisabled() {
        assertThat(context).isNotNull();
        assertThat(propertyRepository).isNotNull();

        // JWT service must NOT exist in application context
        assertThat(context.containsBean("jwtTokenService")).isFalse();
        assertThat(propertyRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
