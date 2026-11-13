package com.homestay.pms_backend.security;

import com.homestay.pms_backend.repository.PropertyRepository;
import com.homestay.pms_backend.test.IsolatedDatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@IsolatedDatabaseTest
@TestPropertySource(properties = {
        "auth.datasource.enabled=false",
        "TEST_AUTH_DATASOURCE_ENABLED=false"
})
@DisplayName("Disabled Auth Context: Normal PMS application context loads without auth DataSource")
class DisabledAuthDataSourceIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PropertyRepository propertyRepository;

    @Test
    @DisplayName("Context loads successfully without auth beans when auth.datasource.enabled=false")
    void shouldLoadNormalPmsContextWhenAuthDataSourceDisabled() {
        assertThat(context).isNotNull();
        assertThat(dataSource).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
        assertThat(propertyRepository).isNotNull();

        // Auth beans must NOT exist in the context when disabled
        assertThat(context.containsBean("authDataSource")).isFalse();
        assertThat(context.containsBean("authJdbcTemplate")).isFalse();
        assertThat(context.containsBean("authTransactionManager")).isFalse();
        assertThat(context.containsBean("authTransactionTemplate")).isFalse();
        assertThat(context.containsBean("staffIdentityLookup")).isFalse();
        assertThat(context.containsBean("staffAuthenticationService")).isFalse();

        // Core domain functionality continues using pms_app
        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        assertThat(currentUser).isEqualTo("pms_app");
        assertThat(propertyRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
