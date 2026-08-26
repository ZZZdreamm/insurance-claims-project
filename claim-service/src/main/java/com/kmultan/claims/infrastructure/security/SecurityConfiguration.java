package com.kmultan.claims.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.security.ResourceServerSecurityConfiguration;

/**
 * URL-level rules for claim-service. Everything under /api needs a token except
 * login; role and ownership rules live on the controllers and in
 * {@link com.kmultan.claims.api.ClaimAccessPolicy}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final String[] PUBLIC_ENDPOINTS = {"/api/v1/auth/login"};

    @Bean
    public SecurityFilterChain claimApi(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper)
            throws Exception {
        return ResourceServerSecurityConfiguration.statelessBearerApi(
                http, jwtDecoder, jwtAuthenticationConverter, objectMapper, rules -> rules.requestMatchers(
                                PUBLIC_ENDPOINTS)
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
