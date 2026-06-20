package com.example.expense;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 認証フローの統合テスト (signup/login/refresh/logout/RBAC)。
 * メソッドごとに X-Forwarded-For を変えて Rate Limit の相互干渉を避ける。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final String PW = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private JsonNode login(String email, String xff) throws Exception {
        String res = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", xff)
                        .contentType("application/json")
                        .content(json(Map.of("email", email, "password", PW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res);
    }

    @Test
    void signupCreatesUserRole() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(json(Map.of("email", "newbie@example.com", "password", PW, "name", "Newbie"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void signupDuplicateEmailConflicts() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(json(Map.of("email", "admin@example.com", "password", PW, "name", "X"))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .contentType("application/json")
                        .content(json(Map.of("email", "user@example.com", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsProfile() throws Exception {
        String token = login("user@example.com", "10.0.0.2").get("accessToken").asText();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void userListIsAdminOnly() throws Exception {
        String userToken = login("user@example.com", "10.0.0.3").get("accessToken").asText();
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        String adminToken = login("admin@example.com", "10.0.0.4").get("accessToken").asText();
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanPromoteUser() throws Exception {
        // 昇格対象を新規作成し、その id を取得 (seed ユーザーには触れない)
        String created = mockMvc.perform(post("/api/auth/signup")
                        .contentType("application/json")
                        .content(json(Map.of("email", "promote@example.com", "password", PW, "name", "P"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long targetId = objectMapper.readTree(created).get("id").asLong();

        String adminToken = login("admin@example.com", "10.0.0.5").get("accessToken").asText();
        mockMvc.perform(put("/api/admin/users/{id}/role", targetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(json(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void refreshRotatesAndIdempotentReplayWithinGrace() throws Exception {
        JsonNode first = login("user@example.com", "10.0.0.6");
        String refresh = first.get("refreshToken").asText();

        // 1回目の refresh -> 新しいペア
        String res1 = mockMvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", "10.0.0.6")
                        .contentType("application/json")
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefresh = objectMapper.readTree(res1).get("refreshToken").asText();

        // 同じ旧トークンを grace window 内に再提示 -> 冪等に同じ後継を返す (200, 盗難扱いしない)
        String res2 = mockMvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", "10.0.0.6")
                        .contentType("application/json")
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replayRefresh = objectMapper.readTree(res2).get("refreshToken").asText();

        org.assertj.core.api.Assertions.assertThat(replayRefresh).isEqualTo(newRefresh);
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        JsonNode tokens = login("user@example.com", "10.0.0.7");
        String refresh = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType("application/json")
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());

        // 失効後の refresh は 401 (grace 経過後でなくても revoked かつ replay 無しなので盗難扱い/401)
        mockMvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", "10.0.0.7")
                        .contentType("application/json")
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }
}
