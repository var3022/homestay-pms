package com.homestay.pms_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Enterprise Password Encoder Configuration.
 * Configures BCryptPasswordEncoder with a secure work factor of 12 as required by
 * PMS Phase 2 security policy and the V8/V9 database check constraints.
 */
@Configuration
public class PasswordEncoderConfig {

    public static final int BCRYPT_WORK_FACTOR = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_WORK_FACTOR);
    }
}
