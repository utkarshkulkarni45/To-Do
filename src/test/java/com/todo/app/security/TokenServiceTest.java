package com.todo.app.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void aTokenRoundTripsThePrincipalOutOfItsClaims() {
        TokenService service = service(SECRET, "to-do-app", 60);
        AuthUser principal = new AuthUser(42L, "demo@todo.local", "ADMIN");

        String token = service.issueAccessToken(principal, "Demo Person");
        AuthUser read = service.readPrincipal(token);

        assertThat(read.id()).isEqualTo(42L);
        assertThat(read.role()).isEqualTo("ADMIN");
        assertThat(read.isAdmin()).isTrue();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        TokenService issuer = service(SECRET, "to-do-app", 60);
        TokenService other = service(Base64.getEncoder().encodeToString(
                "ffffffffffffffffffffffffffffffff".getBytes()), "to-do-app", 60);
        String token = issuer.issueAccessToken(new AuthUser(1L, "a@todo.local", "USER"), "A");

        assertThatThrownBy(() -> other.readPrincipal(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsReportedSeparatelySoTheClientCanRefresh() {
        TokenService service = service(SECRET, "to-do-app", -1);
        String token = service.issueAccessToken(new AuthUser(1L, "a@todo.local", "USER"), "A");

        assertThatThrownBy(() -> service.readPrincipal(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        TokenService service = service(SECRET, "to-do-app", 60);
        String token = service(SECRET, "something-else", 60)
                .issueAccessToken(new AuthUser(1L, "a@todo.local", "USER"), "A");

        assertThatThrownBy(() -> service.readPrincipal(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperingWithThePayloadBreaksTheSignature() {
        TokenService service = service(SECRET, "to-do-app", 60);
        String token = service.issueAccessToken(new AuthUser(1L, "a@todo.local", "USER"), "A");
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"role\":\"ADMIN\"}".getBytes()) + "." + parts[2];

        assertThatThrownBy(() -> service.readPrincipal(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiryIsPublishedToTheClientAsALifetime() {
        assertThat(service(SECRET, "to-do-app", 90).expirationSeconds()).isEqualTo(5400L);
    }

    private static TokenService service(String secret, String issuer, long minutes) {
        return new TokenService(new JwtProperties(secret, issuer, minutes),
                new JwtKeySupplier(new JwtProperties(secret, issuer, minutes)));
    }
}
