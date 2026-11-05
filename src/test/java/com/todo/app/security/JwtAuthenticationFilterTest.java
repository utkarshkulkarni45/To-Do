package com.todo.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.app.exception.ErrorBodyWriter;
import com.todo.app.exception.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private JwtAuthenticationFilter filter;
    private TokenService tokens;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(SECRET, "to-do-app", 60);
        tokens = new TokenService(properties, new JwtKeySupplier(properties));
        filter = new JwtAuthenticationFilter(tokens, new ErrorBodyWriter(new ObjectMapper()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aBearerTokenBecomesAnAuthenticatedPrincipal() throws Exception {
        request.addHeader("Authorization", "Bearer "
                + tokens.issueAccessToken(new AuthUser(9L, "demo@todo.local", "USER"), "Demo"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(((AuthUser) authentication.getPrincipal()).id()).isEqualTo(9L);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void aRequestWithoutTheHeaderContinuesUnauthenticated() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aNonBearerSchemeIsIgnoredRatherThanRejected() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aForgedTokenIsRejectedBeforeTheRequestReachesTheController() throws Exception {
        request.addHeader("Authorization", "Bearer not.a.jwt");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains(ErrorCodes.TOKEN_INVALID);
    }
}
