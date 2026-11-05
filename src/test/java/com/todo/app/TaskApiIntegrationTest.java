package com.todo.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The journey a reviewer follows: an account, a task, the filters, and what happens when two tabs
 * write the same task. Everything runs against a real PostgreSQL, so the SQL the services ask for is
 * the SQL that is actually executed.
 */
class TaskApiIntegrationTest extends ApiIntegrationTestSupport {

    @Autowired
    private com.todo.app.repository.TaskRepository taskRepository;

    @Test
    void aFreshAccountGetsATokenItCanUseImmediately() throws Exception {
        String email = email();
        String token = register(email);

        mockMvc.perform(get("/api/auth/me", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void aSecondAccountOnTheSameAddressIsRefused() throws Exception {
        String email = email();
        register(email);

        mockMvc.perform(post("/api/auth/register", null, account(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void aWrongPasswordGivesTheSameAnswerAsAnUnknownAddress() throws Exception {
        String email = email();
        register(email);

        MvcResult result = mockMvc.perform(post("/api/auth/login", null,
                        Map.of("email", email, "password", "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(body(result).get("message").asText()).doesNotContain(email);
    }

    @Test
    void aMissingTokenIsRefusedWithTheStandardBody() throws Exception {
        mockMvc.perform(get("/api/tasks", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_MISSING"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aTaskIsCreatedReadUpdatedAndSoftDeleted() throws Exception {
        String token = register(email());
        long id = create(token, task("Write the migration", 2));

        mockMvc.perform(get("/api/tasks/" + id, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Write the migration"))
                .andExpect(jsonPath("$.version").value(0));

        Map<String, Object> edit = new HashMap<>(task("Write the migration by hand", 2));
        edit.put("version", 0L);
        mockMvc.perform(put("/api/tasks/" + id, token, edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(delete("/api/tasks/" + id, token)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tasks/" + id, token)).andExpect(status().isNotFound());

        // Soft delete keeps the row; the retention job is what eventually removes it.
        assertThat(taskRepository.findById(id)).isPresent();
    }

    @Test
    void writingWithAVersionThatIsNoLongerCurrentIsRefused() throws Exception {
        String token = register(email());
        long id = create(token, task("Shared between two tabs", 1));

        Map<String, Object> stale = new HashMap<>(task("Written by the other tab", 1));
        stale.put("version", 41L);

        mockMvc.perform(put("/api/tasks/" + id, token, stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));
    }

    @Test
    void anotherAccountSeesNothingOfThisOne() throws Exception {
        long id = create(register(email()), task("Private plan", 1));
        String intruder = register(email());

        mockMvc.perform(get("/api/tasks/" + id, intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(get("/api/tasks", intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aBlankTitleComesBackAsAFieldMap() throws Exception {
        String token = register(email());
        Map<String, Object> missingTitle = new HashMap<>(task("placeholder", 1));
        missingTitle.put("title", "");

        mockMvc.perform(post("/api/tasks", token, missingTitle))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.title").exists());
    }

    @Test
    void filtersSortAndPageAreDoneByTheDatabase() throws Exception {
        String token = register(email());
        long urgent = create(token, task("File the expense report", -1, "URGENT", "TODO"));
        create(token, task("Draft the slides", 3, "MEDIUM", "TODO"));
        long latest = create(token, task("Book the venue", 10, "LOW", "DONE"));

        mockMvc.perform(get("/api/tasks?size=2", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                // The nearest due date is the one that should be at the top of the list.
                .andExpect(jsonPath("$.content[0].id").value(urgent));

        mockMvc.perform(get("/api/tasks?page=1&size=2", token))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/api/tasks?status=TODO", token))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/tasks?priority=URGENT", token))
                .andExpect(jsonPath("$.content[0].id").value(urgent));

        mockMvc.perform(get("/api/tasks?sortBy=dueAt&descending=true", token))
                .andExpect(jsonPath("$.content[0].id").value(latest));

        // A column outside the whitelist is ignored rather than passed into the query.
        mockMvc.perform(get("/api/tasks?sortBy=password", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(urgent));
    }

    @Test
    void keywordSearchCoversTitleAndDescription() throws Exception {
        String token = register(email());
        create(token, withDescription("Interview prep", "Rehearse the sorting questions"));
        create(token, task("Water the plants", 1));

        mockMvc.perform(get("/api/tasks?q=rehearse", token))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Interview prep"));
    }

    @Test
    void anOpenTaskPastItsDueDateIsOverdueAndDoneTasksAreNot() throws Exception {
        String token = register(email());
        long late = create(token, task("Missed deadline", -2));
        create(token, task("Not due yet", 4));

        mockMvc.perform(get("/api/tasks", token))
                .andExpect(jsonPath("$.content[0].id").value(late))
                .andExpect(jsonPath("$.content[0].overdue").value(true));

        mockMvc.perform(get("/api/tasks?overdue=true", token))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/api/tasks/bulk-status", token,
                        Map.of("ids", List.of(late), "status", "DONE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].completedAt").exists());

        mockMvc.perform(get("/api/tasks?overdue=true", token))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void statsAddUpAndTagsBecomeAFilter() throws Exception {
        String token = register(email());
        long tagged = create(token, withTags("Read the specification", List.of("databases", "exam")));

        mockMvc.perform(get("/api/tags", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder("databases", "exam")));

        mockMvc.perform(get("/api/tasks?tagId=" + tagId(token, "exam"), token))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tagged));

        mockMvc.perform(get("/api/tasks/stats", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.todo").value(1))
                .andExpect(jsonPath("$.overdue").value(0));
    }

    @Test
    void patchChangesOnlyTheFieldItSends() throws Exception {
        String token = register(email());
        long id = create(token, withDescription("Keep the notes", "These must survive the patch"));

        mockMvc.perform(patch("/api/tasks/" + id, token, Map.of("status", "IN_PROGRESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.description").value("These must survive the patch"));
    }

    @Test
    void bulkStatusRefusesIdsThatBelongToSomeoneElse() throws Exception {
        String token = register(email());
        long mine = create(token, task("Mine", 1));
        long theirs = create(register(email()), task("Theirs", 1));

        mockMvc.perform(post("/api/tasks/bulk-status", token,
                        Map.of("ids", List.of(mine, theirs), "status", "DONE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(get("/api/tasks/" + mine, token))
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    private long create(String token, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks", token, payload))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).get("id").asLong();
    }

    private long tagId(String token, String name) throws Exception {
        JsonNode tags = body(mockMvc.perform(get("/api/tags", token)).andExpect(status().isOk()).andReturn());
        for (JsonNode tag : tags) {
            if (name.equals(tag.get("name").asText())) {
                return tag.get("id").asLong();
            }
        }
        throw new AssertionError("Tag " + name + " was never created");
    }

    private static Map<String, Object> account(String email) {
        return Map.of("email", email, "fullName", "Test Account", "password", PASSWORD);
    }

    private static Map<String, Object> task(String title, int dueInDays) {
        return task(title, dueInDays, "MEDIUM", "TODO");
    }

    private static Map<String, Object> task(String title, int dueInDays, String priority, String status) {
        return Map.of("title", title, "status", status, "priority", priority,
                "dueAt", Instant.now().plus(dueInDays, ChronoUnit.DAYS).toString());
    }

    private static Map<String, Object> withDescription(String title, String description) {
        Map<String, Object> payload = new HashMap<>(task(title, 1));
        payload.put("description", description);
        return payload;
    }

    private static Map<String, Object> withTags(String title, List<String> tags) {
        Map<String, Object> payload = new HashMap<>(task(title, 1));
        payload.put("tags", tags);
        return payload;
    }

    private static String email() {
        return UUID.randomUUID() + "@todo.test";
    }
}
