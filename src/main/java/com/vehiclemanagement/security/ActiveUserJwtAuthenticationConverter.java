package com.vehiclemanagement.security;

import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.repo.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Turns a valid signature into a valid session — or refuses it.
 *
 * <p><b>Why this costs a database read on every request.</b> A signed JWT is valid until it
 * expires and nothing else, so with an eight-hour token a deactivated account would keep working
 * for the rest of the day and a changed password would lock nobody out. Loading the user here
 * closes both: the row says whether the account still exists, is still active and still has a
 * login, and {@code password_changed_at} says whether this particular token predates the last
 * credential change. It is one primary-key read, which is the cheapest query the database has.
 *
 * <p><b>What it still does not do</b> is revoke one specific stolen token while leaving the
 * account alone. That needs a denylist, and is the follow-on if it is ever wanted.
 *
 * <p>Authorities come from the <b>database row</b>, not from the token's {@code user_type}
 * claim. A demoted administrator loses access on their next request rather than on expiry, and a
 * token cannot carry a privilege the account no longer has.
 */
public final class ActiveUserJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    public ActiveUserJwtAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long id;
        try {
            id = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidBearerTokenException("Malformed token.");
        }

        User user = users.findById(id)
                .orElseThrow(() -> new InvalidBearerTokenException("That account no longer exists."));
        if (!user.isActive()) {
            throw new InvalidBearerTokenException("That account has been deactivated.");
        }
        if (!user.canSignIn()) {
            throw new InvalidBearerTokenException("That account no longer has a login.");
        }
        if (issuedBeforeLastPasswordChange(jwt, user)) {
            throw new InvalidBearerTokenException(
                    "The password for this account has changed. Sign in again.");
        }

        // From `role`, not `user_type`. Those were the same column until changeset 029, which
        // meant a person's authority could not be changed without changing what they were --
        // and made "office staff who administers accounts" unrepresentable.
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(Roles.PREFIX + user.getRole().name()));
        return new JwtAuthenticationToken(jwt, authorities, user.getUsername());
    }

    /**
     * Whether this token was minted before the account's credentials last changed.
     *
     * <p>Compared at millisecond resolution, from the private {@code iat_ms} claim rather than
     * the standard {@code iat}. At {@code iat}'s one-second resolution there is no right answer:
     * truncating both sides lets a token minted in the same second as a password change survive
     * it, and not truncating rejects the token the change itself produced, so the user cannot
     * sign in afterwards. See JwtService.
     *
     * <p>A token with no {@code iat_ms} falls back to the truncated-second comparison, which is
     * the safe direction — it can only ever accept a token within one second of the change,
     * never reject a valid one.
     */
    private static boolean issuedBeforeLastPasswordChange(Jwt jwt, User user) {
        if (user.getPasswordChangedAt() == null) {
            return false;
        }
        Instant changed = user.getPasswordChangedAt().toInstant();
        Object issuedMillis = jwt.getClaim("iat_ms");
        if (issuedMillis instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue()).isBefore(changed);
        }
        if (jwt.getIssuedAt() == null) {
            return false;
        }
        return jwt.getIssuedAt().truncatedTo(ChronoUnit.SECONDS)
                .isBefore(changed.truncatedTo(ChronoUnit.SECONDS));
    }
}
