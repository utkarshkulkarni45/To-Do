package com.todo.app.config;

import com.todo.app.security.AuthUser;
import com.todo.app.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            try {
                AuthUser user = CurrentUser.require();
                return Optional.of(user.email());
            } catch (RuntimeException anonymousOrSystemCall) {
                return Optional.of("system");
            }
        };
    }
}
