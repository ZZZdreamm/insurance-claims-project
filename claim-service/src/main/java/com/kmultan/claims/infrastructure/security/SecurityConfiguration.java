package com.kmultan.claims.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.security.ResourceServerSecurityConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

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
    public SecurityFilterChain claimApi(HttpSecurity http, JwtDecoder jwtDecoder,
                                        JwtAuthenticationConverter jwtAuthenticationConverter, ObjectMapper objectMapper) throws Exception {
        return ResourceServerSecurityConfiguration.statelessBearerApi(http, jwtDecoder, jwtAuthenticationConverter, objectMapper,
                rules -> rules.requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                              .requestMatchers("/api/**").authenticated());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${claims.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Location", "Idempotent-Replayed", "X-RateLimit-Remaining", "Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
