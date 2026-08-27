package com.kmultan.payout.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.security.ResourceServerSecurityConfiguration;

/** The ledger and the dead-letter replay are finance/admin tools. */
@Configuration
public class SecurityConfiguration {

    private static final String[] FINANCE_ENDPOINTS = {"/api/v1/dlq/**", "/api/v1/payouts/**"};

    @Bean
    public SecurityFilterChain payoutApi(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper)
            throws Exception {
        return ResourceServerSecurityConfiguration.statelessBearerApi(
                http, jwtDecoder, jwtAuthenticationConverter, objectMapper, rules -> rules.requestMatchers(
                                "/api/v1/payments/callback")
                        .permitAll() // shared-token auth in the controller: the gateway is not a JWT client
                        .requestMatchers(FINANCE_ENDPOINTS)
                        .hasAnyRole("FINANCE", "ADMIN"));
    }
}
