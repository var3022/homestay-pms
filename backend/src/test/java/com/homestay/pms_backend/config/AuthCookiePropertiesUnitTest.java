package com.homestay.pms_backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 3.3C.5.2: AuthCookieProperties Production Security Unit Tests")
class AuthCookiePropertiesUnitTest {

    @Test
    @DisplayName("Unit: Production defaults satisfy all security constraints")
    void shouldAcceptProductionDefaults() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.validate();

        assertThat(properties.isSecure()).isTrue();
        assertThat(properties.getCookieName()).isEqualTo(AuthCookieProperties.PROD_COOKIE_NAME);
        assertThat(properties.getSameSite()).isEqualTo("Strict");
        assertThat(properties.getPath()).isEqualTo("/");
        assertThat(properties.getTtlDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("Unit: Insecure cookies (secure=false) without explicit dev mode are rejected on startup")
    void shouldRejectInsecureCookiesWithoutExplicitDevMode() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(false);
        properties.setAllowInsecureDevMode(false);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure cookies (auth.cookie.secure=false) are strictly forbidden");
    }

    @Test
    @DisplayName("Unit: Cookies with __Host- prefix are rejected if secure=false even in dev mode")
    void shouldRejectHostPrefixWhenInsecure() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(false);
        properties.setAllowInsecureDevMode(true);
        properties.setName("__Host-pms-refresh");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cookies with __Host- prefix must have secure=true");
    }

    @Test
    @DisplayName("Unit: Insecure cookies with explicit dev mode default to pms-refresh-dev")
    void shouldDefaultToDevNameWhenInsecureDevModeAllowed() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(false);
        properties.setAllowInsecureDevMode(true);
        properties.setName(null);

        properties.validate();

        assertThat(properties.getCookieName()).isEqualTo(AuthCookieProperties.DEV_COOKIE_NAME);
    }

    @Test
    @DisplayName("Unit: Production cookies without __Host- prefix are rejected")
    void shouldRejectNonHostPrefixInProduction() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(true);
        properties.setName("insecure-name");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production refresh cookies must use the __Host- prefix");
    }

    @Test
    @DisplayName("Unit: Non-Strict SameSite configuration is rejected")
    void shouldRejectNonStrictSameSite() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSameSite("Lax");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.cookie.same-site must be 'Strict'");
    }

    @Test
    @DisplayName("Unit: Non-root path is rejected for __Host- cookies")
    void shouldRejectNonRootPath() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setPath("/api");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.cookie.path must be '/'");
    }

    @Test
    @DisplayName("Unit: Production profile strictly rejects secure=false even if dev mode requested")
    void shouldRejectSecureFalseInProductionProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");

        AuthCookieProperties properties = new AuthCookieProperties(env);
        properties.setSecure(false);
        properties.setAllowInsecureDevMode(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure cookies (auth.cookie.secure=false) are strictly forbidden in production profile");
    }

    @Test
    @DisplayName("Unit: Production profile strictly rejects allow-insecure-dev-mode=true")
    void shouldRejectInsecureDevModeInProductionProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        AuthCookieProperties properties = new AuthCookieProperties(env);
        properties.setSecure(true);
        properties.setAllowInsecureDevMode(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.cookie.allow-insecure-dev-mode cannot be enabled in production profile");
    }

    @Test
    @DisplayName("Unit: Production profile accepts valid secure production cookie configuration")
    void shouldAcceptValidConfigurationInProductionProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        AuthCookieProperties properties = new AuthCookieProperties(env);
        properties.setSecure(true);
        properties.setAllowInsecureDevMode(false);
        properties.setName("__Host-pms-refresh");
        properties.setSameSite("Strict");
        properties.setPath("/");

        properties.validate();

        assertThat(properties.isSecure()).isTrue();
        assertThat(properties.getCookieName()).isEqualTo("__Host-pms-refresh");
        assertThat(properties.getSameSite()).isEqualTo("Strict");
        assertThat(properties.getPath()).isEqualTo("/");
    }
}
