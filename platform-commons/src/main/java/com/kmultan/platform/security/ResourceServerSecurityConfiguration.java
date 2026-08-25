package com.kmultan.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.web.ProblemDetails;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Everything a service needs to accept claim-service tokens. Each service still
 * declares its own {@link SecurityFilterChain} with its URL rules, built through
 * {@link #statelessBearerApi} so the boring parts (no sessions, no CSRF, problem
 * responses for 401/403, actuator open) are identical everywhere.
 */
@Configuration
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class ResourceServerSecurityConfiguration {

    public static final String[] PUBLIC_ACTUATOR_ENDPOINTS = {"/actuator/health/**", "/actuator/prometheus", "/actuator/info"};

    @Bean
    public SecretKey jwtSecretKey(PlatformSecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(), "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, PlatformSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> JwtClaims.rolesOf(jwt).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(JwtClaims.ROLE_AUTHORITY_PREFIX + role))
                .toList());
        return converter;
    }

    /**
     * Applies the platform defaults and hands the URL rules back to the caller.
     * Usage: {@code statelessBearerApi(http, decoder, converter, objectMapper, rules -> rules.requestMatchers(...)...)}.
     */
    public static SecurityFilterChain statelessBearerApi(HttpSecurity http, JwtDecoder decoder,
                                                         JwtAuthenticationConverter converter, ObjectMapper objectMapper,
                                                         Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> rules) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(registry -> {
                registry.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ACTUATOR_ENDPOINTS).permitAll();
                rules.customize(registry);
                registry.anyRequest().denyAll();
            })
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, exception) -> ProblemDetails.write(response, objectMapper,
                        HttpStatus.UNAUTHORIZED, "Authentication required", "Send a valid bearer token (POST /api/v1/auth/login)"))
                .accessDeniedHandler((request, response, exception) -> ProblemDetails.write(response, objectMapper,
                        HttpStatus.FORBIDDEN, "Forbidden", "Your role does not allow this action")));
        return http.build();
    }
}
