package com.ragapp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragapp.dto.AuthDto;
import com.ragapp.entity.User;
import com.ragapp.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Document endpoints.
 * Uses Testcontainers for a real PostgreSQL + pgvector database.
 * Spring AI calls are NOT made — the test profile disables real Ollama calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Document Controller Integration Tests")
class DocumentControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("ragtest")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired UserRepository  userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static String accessToken;

    @BeforeEach
    void createTestUser() throws Exception {
        if (userRepository.findByUsername("testuser").isEmpty()) {
            User user = User.builder()
                    .username("testuser")
                    .email("testuser@ragtest.com")
                    .passwordHash(passwordEncoder.encode("Password123!"))
                    .fullName("Test User")
                    .roles(Set.of(User.Role.USER))
                    .enabled(true)
                    .build();
            userRepository.save(user);
        }

        if (accessToken == null) {
            AuthDto.LoginRequest loginReq = new AuthDto.LoginRequest("testuser", "Password123!");
            MvcResult result = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            AuthDto.LoginResponse loginResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(), AuthDto.LoginResponse.class);
            accessToken = loginResponse.accessToken();
        }
    }

    // ─── Auth Tests ───────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /auth/login returns 200 and JWT tokens")
    void login_returns_200_and_tokens() throws Exception {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest("testuser", "Password123!");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value("testuser"));
    }

    @Test
    @Order(2)
    @DisplayName("POST /auth/login returns 401 for wrong credentials")
    void login_returns_401_for_wrong_credentials() throws Exception {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest("testuser", "wrongpassword");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ─── Document Tests ───────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("POST /documents/upload returns 201 with document metadata")
    void upload_returns_201() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain",
                "This is a test document with some content for RAG indexing.".getBytes()
        );

        mockMvc.perform(multipart("/documents/upload")
                        .file(file)
                        .param("index", "false")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").isString())
                .andExpect(jsonPath("$.filename").isString())
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    @Order(4)
    @DisplayName("POST /documents/upload returns 401 without token")
    void upload_requires_authentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/documents/upload").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("GET /documents returns 200 with paginated list")
    void list_documents_returns_200() throws Exception {
        mockMvc.perform(get("/documents")
                        .param("page", "0")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.currentPage").value(0));
    }

    // ─── Health Check ─────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("GET /health returns 200 with status UP")
    void health_returns_200() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("rag-application"));
    }

    // ─── Validation Tests ─────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("POST /auth/register with invalid email returns 400")
    void register_with_invalid_email_returns_400() throws Exception {
        AuthDto.RegisterRequest request = new AuthDto.RegisterRequest(
                "newuser", "not-an-email", "Password123!", null);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @Order(8)
    @DisplayName("POST /chat without token returns 401")
    void chat_requires_authentication() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is RAG?\"}"))
                .andExpect(status().isUnauthorized());
    }
}
