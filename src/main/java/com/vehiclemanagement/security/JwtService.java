package com.vehiclemanagement.security;

import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Issues the bearer tokens this API authenticates with.
 *
 * <p>Stateless: there is no session table, so a token cannot be individually revoked. What can
 * be revoked is an account — deactivating someone, changing their password or removing their
 * login all take effect on their next request, because
 * {@link ActiveUserJwtAuthenticationConverter} re-reads the user row every time. See that class
 * for the trade being made.
 *
 * <p>The {@code user_type} claim is <b>informational only</b>. Authorities are derived from the
 * database row at request time, never from the claim, so demoting an administrator takes effect
 * immediately rather than whenever their current token happens to expire.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, VehicleManagementProperties properties) {
        this.encoder = encoder;
        this.ttl = Duration.ofMinutes(properties.getJwt().getTtlMinutes());
    }

    /**
     * Mint a token for someone who has just proved who they are.
     *
     * <p><b>Why there is an {@code iat_ms} claim beside the standard {@code iat}.</b> A JWT's
     * {@code iat} has one-second resolution, and it is compared against
     * {@code users.password_changed_at} on every request to decide whether the token predates
     * the last credential change. At one-second resolution that comparison has to choose between
     * two wrong answers: reject the token minted <em>by</em> a password change (so nobody can
     * sign in for up to a second afterwards), or accept a token minted in the same second as the
     * change (so the revocation has a one-second hole). A private millisecond claim removes the
     * choice.
     */
    public Issued issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now.truncatedTo(ChronoUnit.SECONDS))
                .expiresAt(expiresAt)
                .claim("iat_ms", now.toEpochMilli())
                .claim("username", user.getUsername())
                .claim("name", user.getName())
                .claim("user_type", user.getUserType().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Issued(token, expiresAt);
    }

    /** A freshly minted token and the moment it stops being accepted. */
    public record Issued(String token, Instant expiresAt) {
    }
}
