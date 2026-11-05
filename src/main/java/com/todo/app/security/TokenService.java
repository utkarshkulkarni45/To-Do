package com.todo.app.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class TokenService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public TokenService(JwtProperties properties, JwtKeySupplier keySupplier) {
        this.properties = properties;
        this.signingKey = keySupplier.getKey();
    }

    public String issueAccessToken(AuthUser user, String fullName) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(properties.expirationMinutes()));
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .issuer(properties.issuer())
                .claim(CLAIM_ROLE, user.role())
                .claim(CLAIM_NAME, fullName)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @throws JwtException when the token is missing a subject, badly formed or signed by another key
     */
    public AuthUser readPrincipal(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthUser(Long.parseLong(claims.getSubject()), claims.get(CLAIM_NAME, String.class),
                claims.get(CLAIM_ROLE, String.class));
    }

    public long expirationSeconds() {
        return Duration.ofMinutes(properties.expirationMinutes()).toSeconds();
    }
}
