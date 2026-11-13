package com.homestay.pms_backend.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition verifying that both auth.datasource.enabled and jwt.enabled are true.
 * Prevents exposing a partially functional or broken authentication endpoint if either
 * subsystem is disabled.
 */
public class AuthApiEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        boolean authDsEnabled = "true".equalsIgnoreCase(env.getProperty("auth.datasource.enabled"));
        boolean jwtEnabled = "true".equalsIgnoreCase(env.getProperty("jwt.enabled"));
        return authDsEnabled && jwtEnabled;
    }
}
