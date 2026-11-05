package com.todo.app.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.todo.app.exception.ErrorBodyWriter;
import com.todo.app.exception.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window limiter in front of the credential endpoints, keyed by client address. It is a first
 * line against password guessing on a single instance; a shared store would be needed to make the
 * limit hold across instances.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> authRateLimitFilter(ErrorBodyWriter errorBodyWriter,
                                                                       AppProperties properties) {
        RateLimitFilter filter = new RateLimitFilter(errorBodyWriter,
                properties.rateLimit().authRequestsPerWindow(), properties.rateLimit().window());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setUrlPatterns(java.util.List.of("/api/auth/login", "/api/auth/register"));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    static class RateLimitFilter extends OncePerRequestFilter {

        private final ErrorBodyWriter errorBodyWriter;
        private final int limit;
        private final Duration window;
        private final Cache<String, Window> windows;

        RateLimitFilter(ErrorBodyWriter errorBodyWriter, int limit, Duration window) {
            this.errorBodyWriter = errorBodyWriter;
            this.limit = limit;
            this.window = window;
            this.windows = Caffeine.newBuilder()
                    .maximumSize(50_000)
                    .expireAfterAccess(window.multipliedBy(2))
                    .build();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Window current = windows.get(request.getRemoteAddr(), address -> new Window(Instant.now().plus(window)));
            if (current.count.incrementAndGet() > limit) {
                long retryAfter = Math.max(1, Duration.between(Instant.now(), current.expiresAt).getSeconds());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
                errorBodyWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                        "Too many authentication attempts. Try again in " + retryAfter + " seconds.");
                return;
            }
            chain.doFilter(request, response);
        }

        private record Window(Instant expiresAt, AtomicInteger count) {

            Window(Instant expiresAt) {
                this(expiresAt, new AtomicInteger());
            }
        }
    }
}
