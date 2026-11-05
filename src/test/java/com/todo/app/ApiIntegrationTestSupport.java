package com.todo.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Boots the whole application against a PostgreSQL that the test JVM starts itself, so the HTTP
 * layer, the security chain, the JPA mapping and the Flyway migrations are exercised together.
 * ToDoContainerIT covers the same ground against the postgres Docker image where Docker exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiIntegrationTestSupport {

    protected static final String PASSWORD = "Str0ngPassw0rd";

    static final EmbeddedPostgres DATABASE = startDatabase();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper json;

    /** Registers an account and returns its bearer token, the way the browser client does. */
    protected String register(String email) throws Exception {
        return accessToken(post("/api/auth/register", null, Map.of(
                "email", email, "fullName", "Test Account", "password", PASSWORD)));
    }

    protected String login(String email) throws Exception {
        return accessToken(post("/api/auth/login", null, Map.of("email", email, "password", PASSWORD)));
    }

    private String accessToken(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andReturn()).get("accessToken").asText();
    }

    protected MockHttpServletRequestBuilder get(String path, String token) {
        return withToken(MockMvcRequestBuilders.get(path), token);
    }

    protected MockHttpServletRequestBuilder post(String path, String token, Map<String, Object> payload)
            throws IOException {
        return withToken(jsonBody(MockMvcRequestBuilders.post(path), payload), token);
    }

    protected MockHttpServletRequestBuilder put(String path, String token, Map<String, Object> payload)
            throws IOException {
        return withToken(jsonBody(MockMvcRequestBuilders.put(path), payload), token);
    }

    protected MockHttpServletRequestBuilder patch(String path, String token, Map<String, Object> payload)
            throws IOException {
        return withToken(jsonBody(MockMvcRequestBuilders.patch(path), payload), token);
    }

    protected MockHttpServletRequestBuilder delete(String path, String token) {
        return withToken(MockMvcRequestBuilders.delete(path), token);
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, Map<String, Object> payload)
            throws IOException {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected JsonNode body(MvcResult result) throws IOException {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The id of a created resource, which every API returns in its "id" field. */
    protected long idOf(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andReturn()).get("id").asLong();
    }

    private static EmbeddedPostgres startDatabase() {
        try {
            return EmbeddedPostgres.builder().setDataDirectory(Files.createTempDirectory("todo-it")).start();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to start the PostgreSQL used by the integration tests", failure);
        }
    }
}
