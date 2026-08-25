package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;
import com.kmultan.claims.domain.auth.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;

/**
 * Demo accounts for a fresh database (password = username, except the service
 * account whose password comes from configuration). Disabled with
 * {@code claims.auth.seed-demo-users=false}; a real deployment provisions
 * accounts through an admin flow or an IdP instead.
 */
@Configuration
public class DemoAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

    @Bean
    public ApplicationRunner seedDemoAccounts(UserAccountRepository accounts, PasswordEncoder passwordEncoder, AuthenticationProperties properties) {
        return arguments -> {
            if (!properties.seedDemoUsers() || accounts.count() > 0) {
                return;
            }
            accounts.save(new UserAccount("anna", passwordEncoder.encode("anna"), "Anna Kowalska", EnumSet.of(Role.POLICYHOLDER)));
            accounts.save(new UserAccount("marek", passwordEncoder.encode("marek"), "Marek Nowak", EnumSet.of(Role.POLICYHOLDER)));
            accounts.save(new UserAccount("alice", passwordEncoder.encode("alice"), "Alice Adjuster", EnumSet.of(Role.ADJUSTER)));
            accounts.save(new UserAccount("bob", passwordEncoder.encode("bob"), "Bob Adjuster", EnumSet.of(Role.ADJUSTER)));
            accounts.save(new UserAccount("finance", passwordEncoder.encode("finance"), "Finance Desk", EnumSet.of(Role.FINANCE)));
            accounts.save(new UserAccount("admin", passwordEncoder.encode("admin"), "Platform Admin", EnumSet.of(Role.ADMIN)));
            accounts.save(new UserAccount("assessment-service", passwordEncoder.encode(properties.serviceAccountPassword()),
                    "assessment-service (machine)", EnumSet.of(Role.SERVICE)));
            log.warn("Seeded demo accounts (anna, marek, alice, bob, finance, admin, assessment-service). Do not ship this to production.");
        };
    }
}
