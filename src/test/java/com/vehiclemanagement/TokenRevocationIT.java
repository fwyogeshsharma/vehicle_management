package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The part of stateless authentication that is easy to get wrong.
 *
 * <p>A signed JWT is valid until it expires and nothing else. Everything here proves that the
 * per-request user read actually closes that gap — because the failure mode is silent: a
 * deactivated account keeps working all day and nobody notices until an audit.
 */
class TokenRevocationIT extends ApiTest {

    private User person;
    private String token;

    @BeforeEach
    void reset() {
        resetDomainData();
        person = users.create("Desk Staff", "9800000002", UserType.STAFF);
        users.setLogin(person.getId(), "desk", PASSWORD, UserRole.CSR);
        token = logIn("desk", PASSWORD);
        assertThat(get("/api/vehicles", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("deactivating someone stops their existing token immediately")
    void a_deactivated_account_loses_its_token() {
        // Straight through the service: the point under test is the token, not who is allowed
        // to press the button.
        users.deactivate(person.getId());

        ResponseEntity<JsonNode> after = get("/api/vehicles", token);

        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("changing a password invalidates tokens issued before it")
    void a_password_change_revokes_the_old_token() {
        ResponseEntity<JsonNode> changed = post("/api/auth/change-password", token,
                body("current_password", PASSWORD, "new_password", "a-brand-new-password"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/api/vehicles", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("and the token minted BY the change still works")
    void the_new_token_is_not_caught_by_its_own_revocation() {
        post("/api/auth/change-password", token,
                body("current_password", PASSWORD, "new_password", "a-brand-new-password"));

        // This is the case the second-truncation exists for: iat has one-second resolution and
        // password_changed_at has microseconds, so comparing them raw rejects the very token the
        // change produced, and nobody can sign in at all.
        String fresh = logIn("desk", "a-brand-new-password");
        assertThat(get("/api/vehicles", fresh).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void the_wrong_current_password_changes_nothing() {
        ResponseEntity<JsonNode> refused = post("/api/auth/change-password", token,
                body("current_password", "not-it", "new_password", "a-brand-new-password"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/vehicles", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void removing_a_login_stops_the_token_too() {
        users.removeLogin(person.getId());

        assertThat(get("/api/vehicles", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("authorities come from the database, not from the token's claim")
    void a_demotion_takes_effect_before_the_token_expires() {
        User root = users.create("Root", "9800000001", UserType.ADMIN);
        users.setLogin(root.getId(), "root", PASSWORD, UserRole.ADMIN);
        String adminToken = logIn("root", PASSWORD);
        assertThat(put("/api/users/" + person.getId() + "/type", adminToken,
                body("user_type", "STAFF")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second administrator, so demoting the first is allowed at all.
        User other = users.create("Deputy", "9800000003", UserType.ADMIN);
        users.setLogin(other.getId(), "deputy", PASSWORD, UserRole.ADMIN);
        // Demoted by ROLE, not by type. Since changeset 029 those are different acts: changing
        // what somebody IS no longer changes what they may DO, so setType would leave this
        // token working and the test would prove nothing.
        users.setRole(root.getId(), UserRole.CSR);

        // The token is untouched and still valid. The row says CSR, and the row wins.
        ResponseEntity<JsonNode> refused = delete("/api/users/" + person.getId(), adminToken);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
