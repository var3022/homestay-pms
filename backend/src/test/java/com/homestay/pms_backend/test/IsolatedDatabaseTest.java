package com.homestay.pms_backend.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Standard test annotation for all database-backed Spring integration tests.
 *
 * Guarantees:
 * 1. Bootstraps Spring Boot test context.
 * 2. Enforces TestDatabaseSafetyInitializer checks (strictly rejects port 5432,
 *    verifies disposable port 5433 reachability, validates credential hygiene).
 * 3. Keeps test configuration isolated from production PMS database.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
public @interface IsolatedDatabaseTest {
}

