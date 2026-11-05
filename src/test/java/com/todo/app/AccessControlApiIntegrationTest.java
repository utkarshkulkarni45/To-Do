package com.todo.app;

import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who is allowed to call what, and what a failure looks like on the wire. These are the checks that
 * service tests with mocked repositories cannot make: the filter chain, the role rules and the error
 * advice have to agree with each other.
 */
class AccessControlApiIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private com.todo.app.repository.UserRepository userRepository;

    @Test
    void anOrdinaryAccountCannotReachTheAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/users", register(email())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void anAdministratorReadsAnAccountAndChangesItsRole() throws Exception {
        String victimEmail = email();
        long victimId = registerAndGetId(victimEmail);
        String adminToken = adminToken();

        mockMvc.perform(get("/api/admin/users/" + victimId, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(victimEmail))
                .andExpect(jsonPath("$.role").value("USER"));

        mockMvc.perform(put("/api/admin/users/" + victimId + "/role", adminToken, Map.of("role", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void anAdministratorCannotDemoteThemselvesOutOfTheirOwnRole() throws Exception {
        String adminToken = adminToken();
        long self = body(mockMvc.perform(get("/api/auth/me", adminToken))
                .andExpect(status().isOk()).andReturn()).get("id").asLong();

        mockMvc.perform(put("/api/admin/users/" + self + "/role", adminToken, Map.of("role", "USER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void aDisabledAccountCanNoLongerSignInButItsRowIsKept() throws Exception {
        String victimEmail = email();
        long victimId = registerAndGetId(victimEmail);

        mockMvc.perform(delete("/api/admin/users/" + victimId, adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login", null, Map.of("email", victimEmail, "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        // Disabling is soft: an audit trail of who had an account is worth more than a clean table.
        assertThat(userRepository.findById(victimId)).isPresent();
    }

    @Test
    void healthAndApiDocumentationAreReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/v3/api-docs", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/tasks.get").exists())
                .andExpect(jsonPath("$.components.schemas.TaskUpsertRequest").exists());
    }

    @Test
    void everyResponseCarriesTheRequestIdThatTracesItInTheLogs() throws Exception {
        mockMvc.perform(get("/api/tasks", register(email())))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));

        // A caller that supplies its own id gets the same one back, which is what makes a log line
        // searchable from the client side.
        mockMvc.perform(get("/api/tasks", register(email())).header("X-Request-Id", "trace-me-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "trace-me-1"));
    }

    @Test
    void frameworkFailuresUseTheSameBodyAsApplicationFailures() throws Exception {
        String token = register(email());

        mockMvc.perform(get("/api/nope", token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());

        mockMvc.perform(MockMvcRequestBuilders.put("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void anEnumInTheQueryThatDoesNotExistIsRefused() throws Exception {
        mockMvc.perform(get("/api/tasks?status=INVENTED", register(email())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void deletingAProjectWithOpenTasksNeedsAnExplicitForce() throws Exception {
        String token = register(email());
        long projectId = idOf(post("/api/projects", token, Map.of("name", "Thesis", "color", "#1F7A5C")));
        Map<String, Object> task = new HashMap<>(Map.of("title", "Chapter one", "status", "TODO",
                "priority", "HIGH", "dueAt", Instant.now().plusSeconds(86_400).toString()));
        task.put("projectId", projectId);
        long taskId = idOf(post("/api/tasks", token, task));

        mockMvc.perform(delete("/api/projects/" + projectId, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("force=true")));

        mockMvc.perform(delete("/api/projects/" + projectId + "?force=true", token))
                .andExpect(status().isNoContent());

        // The task outlives its project and comes back with no project fields at all.
        mockMvc.perform(get("/api/tasks/" + taskId, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.projectName").doesNotExist());

        mockMvc.perform(get("/api/projects", token))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aProjectNameIsUniquePerAccountButNotGlobally() throws Exception {
        String first = register(email());
        idOf(post("/api/projects", first, Map.of("name", "Shared name", "color", "#111111")));

        mockMvc.perform(post("/api/projects", first, Map.of("name", "shared name", "color", "#111111")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        String second = register(email());
        mockMvc.perform(post("/api/projects", second, Map.of("name", "Shared name", "color", "#111111")))
                .andExpect(status().isCreated());
    }

    @Test
    void anInvalidColorIsRejectedBeforeItReachesTheDatabase() throws Exception {
        mockMvc.perform(post("/api/projects", register(email()), Map.of("name", "Ugly", "color", "green")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.color").exists());
    }

    /** Registers an account and returns its id, which the register response publishes. */
    private long registerAndGetId(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register", null, Map.of(
                        "email", email, "fullName", "Test Account", "password", PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("user").get("id").asLong();
    }

    /** A second administrator per run is enough; the test profile never seeds one. */
    private String adminToken() throws Exception {
        String email = email();
        register(email);
        promote(email);
        return login(email);
    }

    private void promote(String email) {
        User user = userRepository.findByEmailAndDeletedFalse(email).orElseThrow();
        user.setRole(Role.ADMIN);
        userRepository.save(user);
    }

    private static String email() {
        return UUID.randomUUID() + "@todo.test";
    }
}
