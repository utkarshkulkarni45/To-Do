package com.todo.app.config;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Starts an embedded PostgreSQL for the dev profile so that `mvn spring-boot:run` works on a machine
 * without Docker. Data lives under target/ and therefore survives restarts; the docker profile and
 * any real deployment point at a PostgreSQL server instead.
 */
@Configuration
@Profile("dev")
@EnableConfigurationProperties(EmbeddedPostgresProperties.class)
@ConditionalOnProperty(name = "app.embedded-postgres.enabled", havingValue = "true")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    /**
     * Flyway and Hibernate are created early, before a bean nothing depends on, so the data source is
     * declared here with a dependency on the server. The url, user and password still come from
     * application-dev.yml; this only fixes which one starts first.
     */
    @Bean
    @DependsOn("embeddedPostgres")
    public DataSource devDataSource(@Value("${spring.datasource.url}") String url,
                                    @Value("${spring.datasource.username}") String username,
                                    @Value("${spring.datasource.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(EmbeddedPostgresProperties properties) {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    .setPort(properties.port())
                    .setDataDirectory(properties.dataDirectory().resolve("embedded-postgres"))
                    .start();
            log.info("Embedded PostgreSQL listening on port {}", postgres.getPort());
            createDatabaseIfMissing(postgres, properties);
            return postgres;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to start the embedded PostgreSQL used by the dev profile", failure);
        }
    }

    private void createDatabaseIfMissing(EmbeddedPostgres postgres, EmbeddedPostgresProperties properties) {
        String maintenanceUrl = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
        try (var connection = DriverManager.getConnection(maintenanceUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + properties.database());
            log.info("Created dev database {}", properties.database());
        } catch (SQLException failure) {
            // 42P04 = duplicate_database, meaning the dev database survived from an earlier run.
            if (!"42P04".equals(failure.getSQLState())) {
                throw new IllegalStateException("Unable to create the dev database " + properties.database(), failure);
            }
            log.debug("Dev database {} already exists", properties.database());
        }
    }
}
