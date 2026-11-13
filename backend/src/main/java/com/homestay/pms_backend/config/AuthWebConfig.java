package com.homestay.pms_backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Web security configuration registering cookie and origin properties.
 */
@Configuration
@EnableConfigurationProperties({
        AuthCookieProperties.class,
        AuthOriginProperties.class
})
public class AuthWebConfig {
}
