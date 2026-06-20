package com.example.expense;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * 領収書アップロードの統合テスト (PostgreSQL + Redis + MinIO Testcontainer + JWT)。
 * Docker が無い環境では自動スキップ。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class ReceiptIntegrationTest {

    private static final String BUCKET = "expense-receipts";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> minio = new GenericContainer<>("minio/minio")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("app.s3.path-style-access", () -> "true");
        registry.add("app.s3.bucket", () -> BUCKET);
        registry.add("app.s3.access-key", () -> "minioadmin");
        registry.add("app.s3.secret-key", () -> "minioadmin");
        registry.add("app.s3.region", () -> "us-east-1");
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private static final byte[] PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String bearer(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", "Password123!"));
        String json = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "55." + email.hashCode() % 200 + ".0.1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).get("accessToken").asText();
    }

    private long createExpense(String token) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "trip", "amount", "1000.00", "category", "transport",
                "expenseDate", java.time.LocalDate.now().toString()));
        String loc = mockMvc.perform(post("/api/expenses")
                        .header("Authorization", token).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1));
    }

    @Test
    void uploadGetAndDeleteReceipt() throws Exception {
        String token = bearer("user@example.com");
        long expenseId = createExpense(token);

        // アップロード
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", PNG);
        String uploaded = mockMvc.perform(multipart("/api/expenses/{id}/receipts", expenseId)
                        .file(file).header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        long receiptId = objectMapper.readTree(uploaded).get("id").asLong();

        // 取得 (presigned URL が返る)
        JsonNode got = objectMapper.readTree(mockMvc.perform(
                        get("/api/expenses/{e}/receipts/{r}", expenseId, receiptId)
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(got.get("url").asText()).startsWith("http");

        // 削除
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/expenses/{e}/receipts/{r}", expenseId, receiptId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    @Test
    void cannotUploadToOthersExpense_IDOR() throws Exception {
        String ownerToken = bearer("user@example.com");
        long expenseId = createExpense(ownerToken);

        // admin (別ユーザー) が他人の経費に領収書を上げようとする -> 404 (所有者スコープ外)
        String adminToken = bearer("admin@example.com");
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", PNG);
        mockMvc.perform(multipart("/api/expenses/{id}/receipts", expenseId)
                        .file(file).header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonImageByMagicBytes() throws Exception {
        String token = bearer("user@example.com");
        long expenseId = createExpense(token);
        MockMultipartFile fake = new MockMultipartFile("file", "fake.png", "image/png",
                "not really a png".getBytes());
        mockMvc.perform(multipart("/api/expenses/{id}/receipts", expenseId)
                        .file(fake).header("Authorization", token))
                .andExpect(status().isBadRequest());
    }
}

