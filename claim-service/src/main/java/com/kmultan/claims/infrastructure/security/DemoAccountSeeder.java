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
public class DemoUsersSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoUsersSeeder.class);

    @Bean
    ApplicationRunner seedDemoUsers(UserAccountRepository users, PasswordEncoder encoder, AuthProperties props) {
        return args -> {
            if (!props.seedDemoUsers() || users.count() > 0) {
                return;
            }
            users.save(new UserAccount("anna", encoder.encode("anna"), "Anna Kowalska", EnumSet.of(Role.POLICYHOLDER)));
            users.save(new UserAccount("marek", encoder.encode("marek"), "Marek Nowak", EnumSet.of(Role.POLICYHOLDER)));
            users.save(new UserAccount("alice", encoder.encode("alice"), "Alice Adjuster", EnumSet.of(Role.ADJUSTER)));
            users.save(new UserAccount("bob", encoder.encode("bob"), "Bob Adjuster", EnumSet.of(Role.ADJUSTER)));
            users.save(new UserAccount("finance", encoder.encode("finance"), "Finance Desk", EnumSet.of(Role.FINANCE)));
            users.save(new UserAccount("admin", encoder.encode("admin"), "Platform Admin", EnumSet.of(Role.ADMIN)));
            users.save(new UserAccount("assessment-service", encoder.encode(props.serviceAccountPassword()), "assessment-service (machine)", EnumSet.of(Role.SERVICE)));
            log.warn("Seeded demo accounts (anna, marek, alice, bob, finance, admin, assessment-service). Do not ship this to production.");
        };
    }
}
