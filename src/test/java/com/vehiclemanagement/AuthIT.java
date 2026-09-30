package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Signing in, and every way of failing to. */
class AuthIT extends ApiTest {

    @Autowired
    private JwtEncoder encoder;

    @BeforeEach
    void reset() {
        resetDomainData();
    }

    @Test
    void a_staff_account_can_sign_in_and_use_the_api() {
        String token = staff();

        ResponseEntity<JsonNode> me = get("/api/auth/me", token);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("username").asText()).isEqualTo("desk");
        // snake_case on the wire, not the Java property name
        assertThat(me.getBody().has("user_type")).isTrue();
        assertThat(me.getBody().has("userType")).isFalse();
    }

    @Test
    @DisplayName("a wrong password and an unknown username are indistinguishable")
    void failures_do_not_reveal_which_usernames_exist() {
        staff();

        ResponseEntity<JsonNode> wrongPassword = post("/api/auth/login", null,
                body("username", "desk", "password", "not-the-password"));
        ResponseEntity<JsonNode> noSuchUser = post("/api/auth/login", null,
                body("username", "nobody-at-all", "password", "not-the-password"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noSuchUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Identical bodies. A different message for each turns this into an account enumerator.
        assertThat(detail(wrongPassword)).isEqualTo(detail(noSuchUser));
    }

    @Test
    @DisplayName("a driver has no login at all, so cannot reach the API")
    void a_driver_cannot_sign_in() {
        User driver = users.create("Suresh Patil", "9811008121", UserType.DRIVER);

        // There is nothing to sign in WITH -- the service refuses to give a driver a login...
        assertThat(driver.canSignIn()).isFalse();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> users.setLogin(driver.getId(), "suresh", PASSWORD, UserRole.CSR))
                .hasMessageContaining("does not sign in");

        // ...and the login endpoint refuses the attempt the same way it refuses any other.
        ResponseEntity<JsonNode> attempt = post("/api/auth/login", null,
                body("username", "suresh", "password", PASSWORD));
        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void no_token_on_a_guarded_path_is_401_not_403() {
        ResponseEntity<JsonNode> response = get("/api/vehicles", null);

        // 403 here would be wrong and is a common mistake: the caller is not forbidden, they are
        // unidentified, and the difference tells a client whether signing in would help.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(detail(response)).isNotBlank();
    }

    @Test
    void a_tampered_signature_is_refused() {
        String token = staff();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(get("/api/vehicles", tampered).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_token_that_is_not_a_token_is_refused() {
        assertThat(get("/api/vehicles", "not-a-jwt").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired token is refused even though its signature is good")
    void an_expired_token_is_refused() {
        String username = "desk";
        staff();
        long userId = users.list(null, UserType.STAFF, null, null,
                org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(u -> username.equals(u.getUsername()))
                .findFirst().orElseThrow().getId();

        Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .issuedAt(longAgo)
                .expiresAt(longAgo.plus(1, ChronoUnit.HOURS))
                .claim("username", username)
                .build();
        String expired = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(get("/api/vehicles", expired).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the login endpoint itself is open, and health is too")
    void the_open_endpoints_are_open() {
        // Reachable without a token: a wrong password must get 401 from the SERVICE, not from
        // the filter chain refusing to let the request through at all.
        assertThat(post("/api/auth/login", null, body("username", "x", "password", "y"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void the_response_never_carries_a_password_hash() {
        String token = staff();

        JsonNode me = get("/api/auth/me", token).getBody();

        assertThat(me.has("password_hash")).isFalse();
        assertThat(me.toString().toLowerCase()).doesNotContain("$2a$");
    }
}
