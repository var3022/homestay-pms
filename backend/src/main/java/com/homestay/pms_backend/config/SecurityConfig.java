package com.homestay.pms_backend.config;

import com.homestay.pms_backend.security.auth.RestAccessDeniedHandler;
import com.homestay.pms_backend.security.auth.RestAuthenticationEntryPoint;
import com.homestay.pms_backend.security.jwt.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Enterprise Spring Security Configuration.
 *
 * Supports two distinct execution modes governed by SecurityEnforcementProperties:
 * 1. Enforcement Enabled:
 *    - All PMS business APIs under /api/** require valid Bearer JWT authentication.
 *    - Stateless session policy (no HTTP sessions created or used for auth).
 *    - Public endpoints strictly restricted to POST /api/v1/auth/login, POST /api/v1/auth/refresh,
 *      POST /api/v1/auth/logout, and GET /actuator/health.
 *    - All other routes denied by default (anyRequest().denyAll()).
 *    - Standardized JSON 401 (RestAuthenticationEntryPoint) and 403 (RestAccessDeniedHandler).
 *    - JwtAuthenticationFilter intercepts requests before UsernamePasswordAuthenticationFilter.
 * 2. Enforcement Disabled (Temporary development and test compatibility):
 *    - /api/** is permitAll to preserve legacy local frontend development without JWT forwarding.
 *    - anyRequest().authenticated() with httpBasic fallback.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SecurityEnforcementProperties enforcementProperties;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(
            SecurityEnforcementProperties enforcementProperties,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) {
        this.enforcementProperties = enforcementProperties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Prevents Spring Boot from automatically registering JwtAuthenticationFilter as a global
     * standalone servlet filter. The filter must execute strictly within Spring Security's
     * filter chain and only when enforcement is enabled.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter
    ) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (enforcementProperties.isEnabled()) {
            http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler)
                    )
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().denyAll()
                    )
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        } else {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/**").permitAll()
                            .anyRequest().authenticated()
                    )
                    .httpBasic(httpBasic -> {});
        }

        return http.build();
    }
}
