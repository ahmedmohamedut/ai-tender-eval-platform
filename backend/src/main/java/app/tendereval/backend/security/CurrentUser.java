package app.tendereval.backend.security;

import java.time.Instant;
import java.util.UUID;

/**
 * The person behind a request, resolved from the token's subject against {@code users}. The role is
 * the one stored for the person (changed only through the admin API); the Chair is never part of it
 * (rule 2).
 */
public record CurrentUser(
    UUID id,
    String subject,
    String email,
    String displayName,
    Role role,
    String status,
    int rowVersion,
    Instant lastSignInAt) {}
