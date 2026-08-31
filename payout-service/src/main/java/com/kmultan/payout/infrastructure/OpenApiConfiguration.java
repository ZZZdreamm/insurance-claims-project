package com.kmultan.payout.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Published at /v3/api-docs (JSON) and /swagger-ui.html; every secured endpoint
 * expects the bearer token issued by claim-service's /api/v1/auth/login.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI payoutServiceApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Payout Service API")
                                .version("v1")
                                .description(
                                        "Part of the insurance claims platform; authenticate with a bearer token issued by claim-service."))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearer"));
    }
}
