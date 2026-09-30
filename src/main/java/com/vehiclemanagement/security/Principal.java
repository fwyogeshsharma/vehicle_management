package com.vehiclemanagement.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reading the caller out of their bearer token.
 *
 * <p>Deliberately thin: the token carries an id and a name, and anything else a controller needs
 * it looks up. Caching a whole user object on the authentication would be a second copy of a row
 * that {@link ActiveUserJwtAuthenticationConverter} has already read, free to drift from it
 * mid-request.
 */
public final class Principal {

    private Principal() {
    }

    /** The signed-in person's id. Never null on a guarded endpoint — the filter chain saw to it. */
    public static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    public static String username(Jwt jwt) {
        return jwt.getClaimAsString("username");
    }
}
