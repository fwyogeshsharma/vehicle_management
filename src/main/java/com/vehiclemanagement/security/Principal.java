package com.vehiclemanagement.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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

    /**
     * The signed-in person's id from the current request, or null when there is none — a
     * seeder, a bootstrap, a test calling a service directly.
     *
     * <p>For services that record WHO did something on every path into them, where threading a
     * token through each overload would be the larger change. Controllers should keep taking
     * the {@link Jwt} as a parameter.
     */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken token ? userId(token.getToken()) : null;
    }

    public static String username(Jwt jwt) {
        return jwt.getClaimAsString("username");
    }
}
