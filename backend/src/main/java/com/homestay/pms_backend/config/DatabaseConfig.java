package com.homestay.pms_backend.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Enterprise Database Configuration implementing strict dual-DataSource segregation:
 *
 * 1. Primary DataSource (pms_app):
 *    - Bound to JPA/Hibernate and standard application transactions.
 *    - Restricted runtime privileges (CRUD on business tables, zero access to password_hash or staff_refresh_tokens).
 *    - Uses Hikari pool "HikariPool-pms-app".
 *
 * 2. Dedicated Auth DataSource (pms_auth):
 *    - Exclusively accessed via qualified JdbcTemplate / TransactionTemplate.
 *    - Uses Hikari pool "HikariPool-pms-auth".
 *    - Strict credentials (reads password_hash for BCrypt validation, full refresh-token lifecycle).
 *    - Zero access to reservations, guests, or financial tables.
 *    - Fails closed if credentials are missing or forbidden roles (pms_admin, pms_app, postgres) are supplied.
 */
@Configuration
@EnableConfigurationProperties(AuthDataSourceProperties.class)
public class DatabaseConfig {

    // -------------------------------------------------------------------------
    // 1. Primary Application DataSource & Transaction Management (pms_app)
    // -------------------------------------------------------------------------

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        HikariDataSource ds = dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if (ds.getPoolName() == null || ds.getPoolName().startsWith("HikariPool-")) {
            ds.setPoolName("HikariPool-pms-app");
        }
        return ds;
    }

    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(name = "transactionTemplate")
    @Primary
    public TransactionTemplate transactionTemplate(@Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    // -------------------------------------------------------------------------
    // 2. Dedicated Authentication DataSource & Transaction Management (pms_auth)
    // -------------------------------------------------------------------------

    @Bean(name = "authDataSource")
    @ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
    public HikariDataSource authDataSource(AuthDataSourceProperties authProps) {
        authProps.validate();

        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("HikariPool-pms-auth");
        ds.setJdbcUrl(authProps.getUrl());
        ds.setUsername(authProps.getUsername());
        ds.setPassword(authProps.getPassword());
        ds.setDriverClassName(authProps.getDriverClassName());
        ds.setMaximumPoolSize(authProps.getMaximumPoolSize());
        ds.setMinimumIdle(authProps.getMinimumIdle());
        ds.setConnectionTimeout(authProps.getConnectionTimeout());
        ds.setIdleTimeout(authProps.getIdleTimeout());
        ds.setMaxLifetime(authProps.getMaxLifetime());
        return ds;
    }

    @Bean(name = "authJdbcTemplate")
    @ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
    public JdbcTemplate authJdbcTemplate(@Qualifier("authDataSource") DataSource authDataSource) {
        return new JdbcTemplate(authDataSource);
    }

    @Bean(name = "authTransactionManager")
    @ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
    public PlatformTransactionManager authTransactionManager(@Qualifier("authDataSource") DataSource authDataSource) {
        return new DataSourceTransactionManager(authDataSource);
    }

    @Bean(name = "authTransactionTemplate")
    @ConditionalOnProperty(prefix = "auth.datasource", name = "enabled", havingValue = "true")
    public TransactionTemplate authTransactionTemplate(@Qualifier("authTransactionManager") PlatformTransactionManager authTransactionManager) {
        return new TransactionTemplate(authTransactionManager);
    }
}
