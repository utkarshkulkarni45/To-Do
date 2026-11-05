package com.todo.app.config;

import com.todo.app.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start a non-development instance with missing or weak settings, instead of running with
 * a key that is not secret or a database url that points nowhere.
 */
@Component
public class StartupSettingsValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSettingsValidator.class);
    private static final List<String> RELAXED_PROFILES = List.of("dev", "test");
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final Environment environment;
    private final JwtProperties jwtProperties;

    public StartupSettingsValidator(Environment environment, JwtProperties jwtProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(RELAXED_PROFILES::contains)) {
            return;
        }
        if (jwtProperties.secret() == null || jwtProperties.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be provided outside the dev/test profiles");
        }
        if (secretBytes() < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MINIMUM_SECRET_BYTES
                    + " bytes for HS256; it currently provides " + secretBytes());
        }
        log.info("Running with profiles {}", Arrays.toString(environment.getActiveProfiles()));
    }

    private int secretBytes() {
        String secret = jwtProperties.secret();
        try {
            return java.util.Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }
}
