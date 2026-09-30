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
 * Where the administrator line actually falls.
 *
 * <p>The policy is: signing in is enough for the <em>domain</em>; administering an
 * <em>account</em> is not. These tests pin both halves, because a guard that is too tight is
 * discovered on day one and a guard that is missing is discovered much later.
 */
class AuthorizationIT extends ApiTest {

    private String staffToken;
    private String adminToken;
    private long adminId;
    private long driverId;

    @BeforeEach
    void reset() {
        resetDomainData();
        User root = users.create("Root Admin", "9800000001", UserType.ADMIN);
        adminId = root.getId();
        users.setLogin(adminId, "root", PASSWORD, UserRole.ADMIN);
        adminToken = logIn("root", PASSWORD);

        User desk = users.create("Desk Staff", "9800000002", UserType.STAFF);
        users.setLogin(desk.getId(), "desk", PASSWORD, UserRole.CSR);
        staffToken = logIn("desk", PASSWORD);

        driverId = users.create("Suresh Patil", "9811008121", UserType.DRIVER).getId();
    }

    @Test
    @DisplayName("staff may do the ordinary work: companies, people, vehicles")
    void staff_can_use_the_domain() {
        assertThat(get("/api/vehicles", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/companies", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/users", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/body-types", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/api/companies", staffToken, body("name", "Kumar Roadways"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("staff may not add a user from the directory endpoint")
    void staff_cannot_add_a_user() {
        ResponseEntity<JsonNode> refused = post("/api/users", staffToken,
                body("name", "New Driver", "mobile", "9811008199", "user_type", "DRIVER"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("but they may create one through intake, which is the scoped exception")
    void staff_can_create_a_driver_while_registering_a_vehicle() {
        long bodyTypeId = jdbc.queryForObject(
                "SELECT id FROM body_types WHERE is_active LIMIT 1", Long.class);

        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", staffToken, body(
                "registration_number", "MH20AB4321", "body_type_id", bodyTypeId,
                "driver_name", "Walk-in Driver", "driver_mobile", "9811008177",
                "company_name", "Walk-in Transport",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The narrow door works; the wide one is still shut.
        assertThat(post("/api/users", staffToken,
                body("name", "Someone Else", "mobile", "9811008166", "user_type", "DRIVER"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("but staff may not grant a login")
    void staff_cannot_create_an_account() {
        long id = users.create("Would-be Staff", "9800000009", UserType.STAFF).getId();

        ResponseEntity<JsonNode> refused = put("/api/users/" + id + "/login", staffToken,
                body("username", "sneaky", "password", "a-good-password", "role", "ADMIN"));

        // This is the escalation that matters: anyone who can create a login can create one for
        // themselves.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(detail(refused)).isNotBlank();
    }

    @Test
    @DisplayName("nor change a role, which is the other way to escalate")
    void staff_cannot_change_a_role() {
        ResponseEntity<JsonNode> refused = put("/api/users/" + driverId + "/type", staffToken,
                body("user_type", "ADMIN"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void staff_cannot_deactivate_a_person() {
        assertThat(delete("/api/users/" + driverId, staffToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete("/api/users/" + driverId + "/login", staffToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void an_administrator_can_do_all_of_it() {
        long id = users.create("Would-be Staff", "9800000009", UserType.STAFF).getId();

        assertThat(put("/api/users/" + id + "/login", adminToken,
                body("username", "newdesk", "password", "a-good-password", "role", "CSR"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put("/api/users/" + driverId + "/type", adminToken, body("user_type", "BOTH"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delete("/api/users/" + driverId, adminToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("the general edit endpoint cannot smuggle a role change through")
    void put_user_ignores_a_user_type_in_the_body() {
        put("/api/users/" + driverId, staffToken,
                body("name", "Suresh Patil", "mobile", "9811008121", "user_type", "ADMIN"));

        assertThat(users.get(driverId).getUserType()).isEqualTo(UserType.DRIVER);
    }

    @Test
    @DisplayName("the last administrator cannot lock everyone out")
    void the_last_admin_is_protected() {
        ResponseEntity<JsonNode> refused = delete("/api/users/" + adminId, adminToken);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("only administrator");
    }

    @Test
    @DisplayName("a driver may not be given a login, whoever asks")
    void even_an_administrator_cannot_give_a_driver_a_login() {
        ResponseEntity<JsonNode> refused = put("/api/users/" + driverId + "/login", adminToken,
                body("username", "suresh", "password", "a-good-password", "role", "CSR"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("does not sign in");
    }

    // ── the role column, added by changeset 029 ─────────────────────────────

    @Test
    @DisplayName("the granted authority comes from role, not from what the person is")
    void authority_comes_from_the_role() {
        // Before changeset 029 these were the same column, so "office staff who administers
        // accounts" was unrepresentable: making them an administrator changed what they were.
        User staff = users.create("Deputy Desk", "9800000009", UserType.STAFF);
        users.setLogin(staff.getId(), "deputy", PASSWORD, UserRole.ADMIN);
        String token = logIn("deputy", PASSWORD);

        // STAFF by type, ADMIN by role -- and it is the role that decides. Asserted against
        // an endpoint that is actually guarded: reading the directory is open to any login.
        assertThat(post("/api/users", token,
                body("name", "New Driver", "mobile", "9811007001", "user_type", "DRIVER"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject(
                "SELECT user_type FROM users WHERE username = 'deputy'", String.class))
                .isEqualTo("STAFF");
    }

    @Test
    @DisplayName("a demotion takes effect on the next request, not the next sign-in")
    void a_demotion_is_immediate() {
        User staff = users.create("Deputy Desk", "9800000009", UserType.STAFF);
        users.setLogin(staff.getId(), "deputy", PASSWORD, UserRole.ADMIN);
        String token = logIn("deputy", PASSWORD);
        assertThat(post("/api/users", token,
                body("name", "New Driver", "mobile", "9811007001", "user_type", "DRIVER"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        users.setRole(staff.getId(), UserRole.CSR);

        // The token is unchanged and still valid; the authority is re-read from the row every
        // call, which is the whole reason it is not a claim.
        assertThat(post("/api/users", token,
                body("name", "Another Driver", "mobile", "9811007002", "user_type", "DRIVER"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("CSR and TEJJJ_CSR are the same as far as any endpoint is concerned")
    void the_two_desk_roles_are_equivalent_today() {
        User a = users.create("Desk A", "9800000007", UserType.STAFF);
        users.setLogin(a.getId(), "deska", PASSWORD, UserRole.CSR);
        User b = users.create("Desk B", "9800000008", UserType.STAFF);
        users.setLogin(b.getId(), "deskb", PASSWORD, UserRole.TEJJJ_CSR);

        int n = 0;
        for (String who : java.util.List.of("deska", "deskb")) {
            String token = logIn(who, PASSWORD);
            // Both may work the domain...
            assertThat(get("/api/vehicles", token).getStatusCode())
                    .as("%s reading vehicles", who).isEqualTo(HttpStatus.OK);
            // ...and neither may create an account, which is the guarded act.
            assertThat(post("/api/users", token,
                    body("name", "Nope " + n, "mobile", "981100700" + n, "user_type", "DRIVER"))
                    .getStatusCode()).as("%s creating an account", who)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            n++;
        }
    }

    @Test
    @DisplayName("a login cannot exist without a role, whoever writes it")
    void the_schema_refuses_a_login_with_no_role() {
        User staff = users.create("Desk C", "9800000006", UserType.STAFF);
        users.setLogin(staff.getId(), "deskc", PASSWORD, UserRole.CSR);

        // Asserted in raw SQL, as SchemaInvariantsIT does: such a row would reach the
        // authority converter with nothing to grant, so it must be unrepresentable rather
        // than merely avoided by the service.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "UPDATE users SET role = NULL WHERE username = 'deskc'")))
                .hasMessageContaining("ck_users_role_pair");

        // And the reverse: a role on somebody who cannot sign in.
        User driver = users.create("Ramesh Kumar", "9811008120", UserType.DRIVER);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "UPDATE users SET role = 'CSR' WHERE id = ?", driver.getId())))
                .hasMessageContaining("ck_users_role_pair");
    }

    @Test
    @DisplayName("only the three roles are storable")
    void the_role_vocabulary_is_closed() {
        User staff = users.create("Desk D", "9800000005", UserType.STAFF);
        users.setLogin(staff.getId(), "deskd", PASSWORD, UserRole.CSR);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "UPDATE users SET role = 'SUPERUSER' WHERE username = 'deskd'")))
                .hasMessageContaining("ck_users_role");
    }

    @Test
    @DisplayName("taking the login away takes the role with it")
    void removing_a_login_clears_the_role() {
        User staff = users.create("Desk E", "9800000004", UserType.STAFF);
        users.setLogin(staff.getId(), "deske", PASSWORD, UserRole.CSR);

        users.removeLogin(staff.getId());

        assertThat(jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, staff.getId())).isNull();
    }

    @Test
    @DisplayName("the last administrator is protected even when their TYPE is not ADMIN")
    void the_guard_follows_the_role_not_the_type() {
        // The ordinary case after changeset 029: office staff who administers. Gating the
        // guard on user_type == ADMIN let exactly this person be deactivated as the last
        // administrator, leaving nobody able to sign in and undo it.
        User onlyAdmin = users.create("Sole Admin", "9800000033", UserType.STAFF);
        users.setLogin(onlyAdmin.getId(), "sole", PASSWORD, UserRole.ADMIN);
        // The fixture's own administrator steps aside, so this really is the only one.
        users.setRole(adminId, UserRole.CSR);

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> users.deactivate(onlyAdmin.getId())))
                .hasMessageContaining("only administrator");
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> users.removeLogin(onlyAdmin.getId())))
                .hasMessageContaining("only administrator");
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> users.setRole(onlyAdmin.getId(), UserRole.CSR)))
                .hasMessageContaining("only administrator");
    }

    @Test
    @DisplayName("deactivating somebody is not deleting them: their work stays attributed")
    void deactivation_keeps_their_history() {
        User desk = users.create("Field Five", "9800000055", UserType.STAFF);
        users.setLogin(desk.getId(), "field5", PASSWORD, UserRole.ADMIN);
        String theirToken = logIn("field5", PASSWORD);

        long laneId = idOf(post("/api/lanes", theirToken, body(
                "from_place", "Meerut", "to_place", "Lucknow", "goods", "Mangoes")));

        users.deactivate(desk.getId());

        // The row they recorded is untouched and still says who recorded it -- recorded_by is
        // a text snapshot, not a foreign key, so there is nothing for a deactivation to blank.
        assertThat(get("/api/lanes/" + laneId, adminToken).getBody().get("recorded_by").asText())
                .isEqualTo("field5");
        // And the person is still in the directory, just inactive.
        assertThat(users.get(desk.getId()).isActive()).isFalse();
    }
}
