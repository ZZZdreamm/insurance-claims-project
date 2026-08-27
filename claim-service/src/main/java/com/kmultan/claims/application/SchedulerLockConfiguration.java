package com.kmultan.claims.application;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * With several claim-service replicas, every replica fires the scheduler.
 * The sweeps are idempotent (escalation and fallback both guard on the claim's
 * state), but running them everywhere multiplies queries and log noise, and a
 * race would surface as optimistic-lock retries. ShedLock elects one runner
 * per tick via a row in Postgres — the same database the sweep reads anyway.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class SchedulerLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
