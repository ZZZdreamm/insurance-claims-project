package com.kmultan.claims.infrastructure.security;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.kmultan.claims.domain.Policy;
import com.kmultan.claims.domain.PolicyRepository;
import com.kmultan.claims.domain.auth.UserAccountRepository;

/**
 * Demo policy book, seeded after the demo accounts. Held policies belong to the
 * demo policyholders; the open POL-1..POL-50 fleet has no holder and exists so
 * load tests can submit without provisioning a policyholder each.
 */
@Configuration
public class PolicySeederConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PolicySeederConfiguration.class);

    @Bean
    @Order(2)
    public ApplicationRunner seedDemoPolicies(
            PolicyRepository policies, UserAccountRepository accounts, AuthenticationProperties properties) {
        return arguments -> {
            if (!properties.seedDemoUsers() || policies.count() > 0) {
                return;
            }
            accounts.findByUsername("anna").ifPresent(anna -> {
                policies.save(new Policy(
                        "POL-123",
                        anna.getId(),
                        Policy.CoverageType.AC,
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2030, 12, 31),
                        new BigDecimal("250000.00"),
                        new BigDecimal("400.00")));
                policies.save(new Policy(
                        "POL-2024-077",
                        anna.getId(),
                        Policy.CoverageType.OC,
                        LocalDate.of(2024, 6, 1),
                        LocalDate.of(2027, 5, 31),
                        new BigDecimal("1000000.00"),
                        BigDecimal.ZERO));
            });
            accounts.findByUsername("marek")
                    .ifPresent(marek -> policies.save(new Policy(
                            "POL-777",
                            marek.getId(),
                            Policy.CoverageType.AC,
                            LocalDate.of(2025, 1, 1),
                            LocalDate.of(2028, 12, 31),
                            new BigDecimal("120000.00"),
                            new BigDecimal("800.00"))));
            for (int fleetNumber = 1; fleetNumber <= 50; fleetNumber++) {
                policies.save(new Policy(
                        "POL-" + fleetNumber,
                        null,
                        Policy.CoverageType.OC,
                        LocalDate.of(2020, 1, 1),
                        LocalDate.of(2035, 12, 31),
                        new BigDecimal("1000000.00"),
                        BigDecimal.ZERO));
            }
            log.warn("Seeded the demo policy book (POL-123, POL-2024-077, POL-777 and the POL-1..50 fleet)");
        };
    }
}
