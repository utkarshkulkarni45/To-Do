package com.todo.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same application against the PostgreSQL image that docker-compose runs, which is the only place
 * the Flyway migrations, the column types and the indexes are checked exactly as they will be in a
 * deployment. It is skipped on a machine without Docker so that a local build stays green.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToDoContainerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper json;

    @Test
    void migrationsCreateTheTablesAndTheIndexesTheListingQueryNeeds() throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select indexname from pg_indexes where tablename in ('tasks', 'projects', 'tags', 'users')")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }

        // The listing index leads with the owner because every query is scoped to one account first.
        assertThat(indexes).contains("ix_tasks_owner_listing", "ix_tasks_due_open");
        // The partial index on due date exists only for rows that can still be overdue.
        assertThat(indexes).doesNotContain("ix_tasks_due_all");
        // The inline PRIMARY KEY constraints are named <table>_pkey; only task_tags spells its key pk_.
        assertThat(indexes).contains("users_pkey", "projects_pkey", "tags_pkey", "tasks_pkey");
    }

    @Test
    void aTaskWrittenThroughTheApiIsReadableFromTheDatabase() throws Exception {
        String email = UUID.randomUUID() + "@todo.test";
        String registerResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email,
                                "fullName", "Container Check", "password", "Str0ngPassw0rd"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(registerResponse).get("accessToken").asText();

        mockMvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "From the container",
                                "status", "TODO", "priority", "LOW"))))
                .andExpect(status().isCreated());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from tasks t join users u on u.id = t.owner_id where u.email = ?")) {
            statement.setString(1, email);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }
}
