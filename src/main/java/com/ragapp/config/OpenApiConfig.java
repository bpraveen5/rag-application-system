package com.ragapp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server().url("/api").description("Default API server"),
                        new Server().url("http://localhost:8080/api").description("Local development")
                ))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME, bearerSecurityScheme()))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }

    private Info apiInfo() {
        return new Info()
                .title("RAG Application API")
                .description("""
                        **Production-ready Retrieval-Augmented Generation API**
                        
                        Features:
                        - Document upload and indexing (PDF, DOCX, TXT, Markdown, HTML)
                        - Semantic and hybrid search via pgvector
                        - Local Ollama-powered chat (llama3.2:3b) with conversation history
                        - JWT-based authentication with role-based access control
                        - Multi-tenant support, rate limiting, audit logging
                        """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("RAG Application Team")
                        .email("team@ragapp.com")
                        .url("https://github.com/ragapp"))
                .license(new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0"));
    }

    private SecurityScheme bearerSecurityScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Enter your JWT access token. Obtain one via POST /auth/login");
    }
}
