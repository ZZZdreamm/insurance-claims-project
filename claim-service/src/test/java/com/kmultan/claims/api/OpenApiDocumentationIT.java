package com.kmultan.claims.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.kmultan.claims.AbstractIntegrationTest;

/** The OpenAPI document is part of the service's contract: published, unauthenticated, and versioned. */
@AutoConfigureMockMvc
class OpenApiDocumentationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openApiDocumentIsPublishedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Claim Service API"))
                .andExpect(jsonPath("$.paths['/api/v1/claims']").exists());
    }
}
