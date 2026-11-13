package com.homestay.pms_backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Business API JWT Request Authentication Enforcement.
 *
 * Temporary compatibility option for development and test rollout:
 * - When enabled: All PMS business endpoints under /api/** require valid Bearer JWT authentication.
 *   Public endpoints are strictly limited to POST /api/v1/auth/login, POST /api/v1/auth/refresh,
 *   POST /api/v1/auth/logout, and GET /actuator/health.
 * - When disabled (development & test only): Preserves existing behavior where /api/** is permitAll
 *   to avoid breaking unmigrated local frontend clients during Phase 2 Step 3.4.
 *
 * Production Startup Guarantees:
 * 1. Startup FAILS if auth.jwt.enforcement.enabled=false in production profile ('prod' or 'production').
 * 2. Startup FAILS if auth.datasource.enabled=false in production profile.
 * 3. Startup FAILS if jwt.enabled=false in production profile.
 * 4. Startup FAILS in any environment if auth.jwt.enforcement.enabled=true but either
 *    auth.datasource.enabled=false or jwt.enabled=false.
 *
 * CRITICAL DEPLOYMENT SAFETY NOTICE:
 * Production deployments MUST explicitly activate the production Spring profile ('prod' or 'production',
 * e.g., via SPRING_PROFILES_ACTIVE=prod or -Dspring.profiles.active=prod).
 * The production startup guard validates that auth.jwt.enforcement.enabled=true, auth.datasource.enabled=true,
 * and jwt.enabled=true. Any deployment without an active production profile is NOT protected by this production
 * startup guard and may inadvertently run in permissive development mode.
 *
 * NOTE: This temporary compatibility flag will be completely removed prior to final production release.
 */
@Component
@ConfigurationProperties(prefix = "auth.jwt.enforcement")
public class SecurityEnforcementProperties implements EnvironmentAware {

    private boolean enabled = false;
    private Environment environment;

    public SecurityEnforcementProperties(@Autowired(required = false) Environment environment) {
        this.environment = environment;
    }

    public SecurityEnforcementProperties() {
        this(null);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isProductionProfileActive() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = isProductionProfileActive();
        boolean authDsEnabled = environment != null && "true".equalsIgnoreCase(environment.getProperty("auth.datasource.enabled"));
        boolean jwtEnabled = environment != null && "true".equalsIgnoreCase(environment.getProperty("jwt.enabled"));

        if (isProd) {
            if (!enabled) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Business API JWT enforcement (auth.jwt.enforcement.enabled=true) " +
                        "is strictly required in production profile. Unauthenticated business APIs are forbidden."
                );
            }
            if (!authDsEnabled) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Authentication DataSource (auth.datasource.enabled=true) " +
                        "is strictly required in production profile."
                );
            }
            if (!jwtEnabled) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: JWT access-token service (jwt.enabled=true) " +
                        "is strictly required in production profile."
                );
            }
        }

        if (enabled) {
            if (!authDsEnabled) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Cannot enable business API JWT enforcement " +
                        "without auth.datasource.enabled=true."
                );
            }
            if (!jwtEnabled) {
                throw new IllegalStateException(
                        "FATAL SECURITY MISCONFIGURATION: Cannot enable business API JWT enforcement " +
                        "without jwt.enabled=true."
                );
            }
        }
    }
}
