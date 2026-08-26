package com.kmultan.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.security.ResourceServerSecurityConfiguration;

/** Search and claim timelines are staff tools: adjusters, finance and admins. */
@Configuration
public class SecurityConfiguration {

    private static final String[] STAFF_ENDPOINTS = {"/api/v1/search/**", "/api/v1/claims/*/events"};

    @Bean
    public SecurityFilterChain searchApi(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper)
            throws Exception {
        return ResourceServerSecurityConfiguration.statelessBearerApi(
                http, jwtDecoder, jwtAuthenticationConverter, objectMapper, rules -> rules.requestMatchers(
                                STAFF_ENDPOINTS)
                        .hasAnyRole("ADJUSTER", "FINANCE", "ADMIN"));
    }
}
