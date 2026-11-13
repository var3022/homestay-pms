package com.homestay.pms_backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 3.4.2: SecurityEnforcementProperties Unit Tests")
class SecurityEnforcementPropertiesUnitTest {

    @Test
    @DisplayName("Default Development State: Enforcement is disabled by default and passes validation in non-prod")
    void shouldPassValidationWhenDisabledInDevelopment() {
        MockEnvironment env = new MockEnvironment();
        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isProductionProfileActive()).isFalse();
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Development Enabled: Passes validation when auth.datasource and jwt are enabled")
    void shouldPassValidationWhenEnabledWithPrerequisitesInDevelopment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThat(props.isEnabled()).isTrue();
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Development Enabled Failure: Throws when enabled without auth.datasource.enabled")
    void shouldThrowWhenEnabledWithoutAuthDataSource() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("auth.datasource.enabled", "false");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.datasource.enabled=true");
    }

    @Test
    @DisplayName("Development Enabled Failure: Throws when enabled without jwt.enabled")
    void shouldThrowWhenEnabledWithoutJwt() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "false");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.enabled=true");
    }

    @Test
    @DisplayName("Production Profile Rejects Disabled Enforcement: Throws when auth.jwt.enforcement.enabled=false")
    void shouldRejectDisabledEnforcementInProductionProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(false);

        assertThat(props.isProductionProfileActive()).isTrue();
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly required in production profile");
    }

    @Test
    @DisplayName("Production Profile Rejects 'prod' Profile with Disabled Enforcement")
    void shouldRejectProdProfileWithDisabledEnforcement() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(false);

        assertThat(props.isProductionProfileActive()).isTrue();
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly required in production profile");
    }

    @Test
    @DisplayName("Production Profile Rejects Disabled Auth DataSource Even If Enforcement Enabled")
    void shouldRejectDisabledAuthDataSourceInProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("auth.datasource.enabled", "false");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication DataSource (auth.datasource.enabled=true)");
    }

    @Test
    @DisplayName("Production Profile Rejects Disabled JWT Even If Enforcement Enabled")
    void shouldRejectDisabledJwtInProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "false");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT access-token service (jwt.enabled=true)");
    }

    @Test
    @DisplayName("Production Profile Passes With All Prerequisites Configured")
    void shouldPassValidationInProductionWhenFullyConfigured() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("auth.datasource.enabled", "true");
        env.setProperty("jwt.enabled", "true");

        SecurityEnforcementProperties props = new SecurityEnforcementProperties(env);
        props.setEnabled(true);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }
}
