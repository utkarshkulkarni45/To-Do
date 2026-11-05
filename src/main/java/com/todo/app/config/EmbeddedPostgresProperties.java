package com.todo.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.embedded-postgres")
public record EmbeddedPostgresProperties(
        @DefaultValue("5433") int port,
        @DefaultValue("todo_dev") String database,
        @DefaultValue("target") Path dataDirectory) {
}
