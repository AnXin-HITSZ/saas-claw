package com.anxin.pyclaw.backend.agentconfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.anxin.pyclaw.backend.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class ToolPolicyGrantValidatorTest {
    private final ToolPolicyGrantValidator validator = new ToolPolicyGrantValidator();

    @Test
    void hostToolsRequireHostGrantAuthority() {
        AgentToolPolicyRequest request = request(List.of("group:host"));
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "admin",
                "password",
                "tool:grant:full"
        );

        assertThrows(ApiException.class, () -> validator.validate(request, authentication));
    }

    @Test
    void hostToolsCanBeGrantedWithHostAuthority() {
        AgentToolPolicyRequest request = request(List.of("host_uname", "host_df"));
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "admin",
                "password",
                "tool:grant:full",
                "tool:grant:host"
        );

        assertDoesNotThrow(() -> validator.validate(request, authentication));
    }

    private AgentToolPolicyRequest request(List<String> alsoAllow) {
        return new AgentToolPolicyRequest("coding", null, List.of(), alsoAllow, true, false, "deny", false);
    }
}