package com.todo.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Tuning knobs for the parts of the application that are not HTTP requests: the account seeded as the
 * first administrator, the rate limiter in front of the credential endpoints and the CORS policy
 * (empty by default, because the browser client is served from the same origin).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("admin@todo.local") String adminEmail,
        @DefaultValue("demo@todo.local") String demoEmail,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Cors cors) {

    public record RateLimit(@DefaultValue("10") int authRequestsPerWindow,
                            @DefaultValue("60s") Duration window) {
    }

    public record Cors(@DefaultValue(" ") List<String> allowedOrigins) {
    }
}
