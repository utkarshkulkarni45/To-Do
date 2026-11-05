package com.todo.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.app.exception.ErrorBodyWriter;
import com.todo.app.exception.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter is checked on its own rather than through the whole application because the property it
 * protects is the number of password guesses one address may try per minute, and a test that has to
 * outlive a real 60 second window is a test nobody runs twice.
 */
class RateLimitConfigTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    void theFourthAttemptInAWindowIsRefusedAndTellsTheClientWhenToRetry() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(3);

        for (int attempt = 1; attempt <= 3; attempt++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(login(), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as("attempt " + attempt).isNotNull();
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        MockFilterChain neverCalled = new MockFilterChain();
        filter.doFilter(login(), blocked, neverCalled);

        assertThat(neverCalled.getRequest()).isNull();
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(blocked.getContentAsString()).contains(ErrorCodes.RATE_LIMITED);
    }

    @Test
    void oneAbusiveClientDoesNotLockOutAnother() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(1);
        filter.doFilter(login(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse neighbour = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest other = login();
        other.setRemoteAddr("198.51.100.7");
        filter.doFilter(other, neighbour, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static RateLimitConfig.RateLimitFilter filter(int limit) {
        return new RateLimitConfig.RateLimitFilter(new ErrorBodyWriter(new ObjectMapper()), limit, WINDOW);
    }

    private static MockHttpServletRequest login() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.9");
        return request;
    }
}
