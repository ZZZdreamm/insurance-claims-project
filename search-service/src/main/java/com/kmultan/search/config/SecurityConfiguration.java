package com.kmultan.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.security.ResourceServerSecurityConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Search is a staff tool: adjusters, finance and admins. */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain searchApi(HttpSecurity http, JwtDecoder jwtDecoder,
                                         JwtAuthenticationConverter jwtAuthenticationConverter, ObjectMapper objectMapper) throws Exception {
        return ResourceServerSecurityConfiguration.statelessBearerApi(http, jwtDecoder, jwtAuthenticationConverter, objectMapper,
                rules -> rules.requestMatchers("/api/v1/search/**").hasAnyRole("ADJUSTER", "FINANCE", "ADMIN"));
    }
}
