package com.todo.app.security;

import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the HMAC signing key from app.jwt.secret. When that is empty (local development)
 * a random key is generated once and kept under target/ so that restarts do not invalidate tokens.
 * Production deployments must supply the secret; see StartupSettingsValidator.
 */
@Component
public class JwtKeySupplier {

    private static final Logger log = LoggerFactory.getLogger(JwtKeySupplier.class);
    private static final int KEY_BYTES = 32;
    private static final Path FALLBACK_KEY_FILE = Path.of("target", "jwt-dev.key");

    private final String configuredSecret;
    private final SecretKey key;

    public JwtKeySupplier(JwtProperties properties) {
        this.configuredSecret = properties.secret();
        this.key = resolveKey();
    }

    public SecretKey getKey() {
        return key;
    }

    public boolean isUsingGeneratedDevKey() {
        return configuredSecret == null || configuredSecret.isBlank();
    }

    private SecretKey resolveKey() {
        if (isUsingGeneratedDevKey()) {
            log.warn("app.jwt.secret is not set; using a generated key stored at {}. "
                    + "Set JWT_SECRET in any environment that must keep sessions across restarts.", FALLBACK_KEY_FILE);
            return readOrCreateDevKey();
        }
        return Keys.hmacShaKeyFor(decode(configuredSecret));
    }

    private byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private SecretKey readOrCreateDevKey() {
        try {
            if (Files.notExists(FALLBACK_KEY_FILE)) {
                Files.createDirectories(FALLBACK_KEY_FILE.getParent());
                byte[] random = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(random);
                Files.writeString(FALLBACK_KEY_FILE, Base64.getEncoder().encodeToString(random) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                restrictToOwner(FALLBACK_KEY_FILE);
            }
            byte[] decoded = decode(Files.readString(FALLBACK_KEY_FILE, StandardCharsets.UTF_8).trim());
            return Keys.hmacShaKeyFor(decoded);
        } catch (FileAlreadyExistsException concurrentlyCreated) {
            return readOrCreateDevKey();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot prepare the development JWT key at " + FALLBACK_KEY_FILE, failure);
        }
    }

    private void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (IOException | UnsupportedOperationException notPosix) {
            log.debug("Could not restrict permissions on {}: {}", file, notPosix.getMessage());
        }
    }
}
