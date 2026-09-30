package com.vehiclemanagement.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** Signing in. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * A bearer token, when it stops working, and who it belongs to.
     *
     * <p>There is <b>no refresh token</b>. Signing in again is the refresh, and logging out is
     * the client discarding this. That is the cost of keeping no session table; a deactivated
     * account or a changed password still takes effect immediately, because the server re-reads
     * the user on every request.
     */
    public record Session(String token, Instant expiresAt, UserDtos.Detail user) {
    }
}
