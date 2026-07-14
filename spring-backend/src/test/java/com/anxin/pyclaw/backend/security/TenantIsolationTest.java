package com.anxin.pyclaw.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anxin.pyclaw.backend.agentconfig.AgentConfigRepository;
import com.anxin.pyclaw.backend.claw.ClawRepository;
import com.anxin.pyclaw.backend.provider.ProviderConfigRepository;
import com.anxin.pyclaw.backend.pyclaw.PyclawClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTest {
    @TempDir
    static java.nio.file.Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClawRepository claws;

    @Autowired
    private ProviderConfigRepository providers;

    @Autowired
    private AgentConfigRepository agents;

    @MockBean
    private PyclawClient pyclawClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            try {
                return "jdbc:h2:file:" + Files.createTempDirectory(tempDir, "db-iso").resolve("pyclaw-test")
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
            } catch (Exception exc) {
                throw new IllegalStateException(exc);
            }
        });
        registry.add("pyclaw.security.jwt-signing-secret", () -> "test-secret-long-enough-for-hs256-tenants");
        registry.add("pyclaw.security.bootstrap-admin-password", () -> "Admin123!");
        registry.add("pyclaw.runtime.internal-token", () -> "internal-token");
    }

    private String userAToken;
    private String userBToken;
    private String userAId;
    private String userBId;
    private String clawAId;
    private String clawBId;

    @BeforeEach
    void setup() throws Exception {
        when(pyclawClient.forwardChannelWebhook(any(), any(), any(byte[].class), any(), anyMap()))
                .thenReturn(ResponseEntity.ok("ok".getBytes()));
        when(pyclawClient.runAgent(any()))
                .thenReturn(new com.anxin.pyclaw.backend.pyclaw.PyclawAgentRunResponse("test-session", java.util.Map.of(), "ok"));

        // Register user A
        MvcResult regA = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"userA\",\"password\":\"PassA123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        userAToken = objectMapper.readTree(regA.getResponse().getContentAsString()).get("accessToken").asText();
        userAId = objectMapper.readTree(regA.getResponse().getContentAsString()).get("userId").asText();

        // Register user B
        MvcResult regB = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"userB\",\"password\":\"PassB123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        userBToken = objectMapper.readTree(regB.getResponse().getContentAsString()).get("accessToken").asText();
        userBId = objectMapper.readTree(regB.getResponse().getContentAsString()).get("userId").asText();

        // User A creates a claw
        MvcResult createA = mockMvc.perform(post("/api/claws")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Claw-A\"}"))
                .andExpect(status().isOk())
                .andReturn();
        clawAId = objectMapper.readTree(createA.getResponse().getContentAsString()).get("id").asText();

        // User B creates a claw
        MvcResult createB = mockMvc.perform(post("/api/claws")
                .header("Authorization", "Bearer " + userBToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Claw-B\"}"))
                .andExpect(status().isOk())
                .andReturn();
        clawBId = objectMapper.readTree(createB.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void userACannotReadUserBClaw() throws Exception {
        mockMvc.perform(get("/api/claws/" + clawBId)
                .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void userACannotUpdateUserBClaw() throws Exception {
        mockMvc.perform(put("/api/claws/" + clawBId)
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"hacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void userACannotDeleteUserBClaw() throws Exception {
        mockMvc.perform(delete("/api/claws/" + clawBId)
                .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void userACannotAccessUserBSandbox() throws Exception {
        mockMvc.perform(get("/api/claws/" + clawBId + "/sandbox/healthz")
                .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void userAProviderNotVisibleToUserB() throws Exception {
        // User A creates a private provider
        mockMvc.perform(post("/api/providers")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A-Private-OpenAI\",\"providerType\":\"openai\",\"model\":\"gpt-4\",\"apiMode\":\"responses\",\"apiKey\":\"sk-a\",\"enabled\":true}"))
                .andExpect(status().isOk());

        MvcResult listB = mockMvc.perform(get("/api/providers")
                .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode providersB = objectMapper.readTree(listB.getResponse().getContentAsString());
        for (JsonNode p : providersB) {
            assertThat(p.get("ownerUserId").asText()).isNotEqualTo(userAId);
        }
    }

    @Test
    void adminCanReadBothClaws() throws Exception {
        String adminToken = loginAdmin();
        mockMvc.perform(get("/api/claws/" + clawAId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/claws/" + clawBId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void secretApiRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/secrets"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sandboxProxyRequiresClawOwnership() throws Exception {
        // Even with a valid token, accessing someone else's sandbox should fail
        mockMvc.perform(get("/api/claws/" + clawBId + "/sandbox/workspace")
                .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    private String loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"Admin123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
