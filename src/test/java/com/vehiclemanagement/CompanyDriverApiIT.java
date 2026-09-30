package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.service.BodyTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "A company's truck takes only a company's driver", as seen by a client.
 *
 * <p>The rule is four database constraints deep. What matters here is that it surfaces as a
 * usable 409 naming the company, and that ending an employment <em>reports</em> the assignments
 * it cascades away rather than dropping them silently.
 */
class CompanyDriverApiIT extends ApiTest {

    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;
    private long kumar;
    private long patel;
    private long hired;
    private long stranger;
    private long companyTruck;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();

        kumar = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));
        patel = idOf(post("/api/companies", token, body("name", "Patel Freight Lines")));

        hired = users.create("Suresh Patil", "9811008121", UserType.DRIVER).getId();
        stranger = users.create("Bhola Yadav", "9811008126", UserType.DRIVER).getId();
        post("/api/users/" + hired + "/companies", token,
                body("company_id", kumar, "position", "Driver", "primary", true));

        companyTruck = idOf(post("/api/vehicles", token, body(
                "registration_number", "MH14CD5678", "body_type_id", bodyTypeId,
                "owner_company_id", kumar,
                "no_of_axles", 3, "no_of_wheels", 10, "capacity", "32 Ton", "length_ft", 32)));
    }

    @Test
    void a_driver_on_the_books_may_be_assigned() {
        ResponseEntity<JsonNode> assigned = post("/api/vehicles/" + companyTruck + "/drivers",
                token, body("user_id", hired, "primary", true));

        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(assigned.getBody()).hasSize(1);
        assertThat(assigned.getBody().get(0).get("name").asText()).isEqualTo("Suresh Patil");
        assertThat(assigned.getBody().get(0).get("primary").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("someone off the payroll is refused, and told whose payroll to join")
    void a_stranger_cannot_drive_a_company_truck() {
        ResponseEntity<JsonNode> refused = post("/api/vehicles/" + companyTruck + "/drivers",
                token, body("user_id", stranger, "primary", false));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("Kumar Roadways");
        assertThat(detail(refused)).doesNotContain("fk_vxu");
    }

    @Test
    @DisplayName("being on a DIFFERENT company's books is not enough")
    void the_right_company_is_the_owning_one() {
        post("/api/users/" + stranger + "/companies", token,
                body("company_id", patel, "position", "Driver", "primary", true));

        ResponseEntity<JsonNode> refused = post("/api/vehicles/" + companyTruck + "/drivers",
                token, body("user_id", stranger, "primary", false));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("an owner-operator may hand the keys to anyone: no payroll to be on")
    void a_person_owned_truck_has_no_such_rule() {
        long owner = users.create("Gurpreet Singh", "9811008122", UserType.BOTH).getId();
        long ownTruck = idOf(post("/api/vehicles", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "owner_user_id", owner,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22)));

        ResponseEntity<JsonNode> assigned = post("/api/vehicles/" + ownTruck + "/drivers", token,
                body("user_id", stranger, "primary", true));

        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("leaving a company reports the truck assignments that went with it")
    void ending_an_employment_says_what_it_cost() {
        post("/api/vehicles/" + companyTruck + "/drivers", token,
                body("user_id", hired, "primary", true));

        ResponseEntity<JsonNode> left = delete("/api/users/" + hired + "/companies/" + kumar,
                token);

        assertThat(left.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The cascade is what keeps the rule true. A silent 204 would hide it.
        assertThat(left.getBody().get("removed_driver_assignments").asLong()).isEqualTo(1);
        assertThat(get("/api/vehicles/" + companyTruck + "/drivers", token).getBody()).isEmpty();
    }

    @Test
    void a_second_primary_driver_replaces_the_first_rather_than_conflicting() {
        long second = users.create("Mohammed Irfan", "9811008123", UserType.DRIVER).getId();
        post("/api/users/" + second + "/companies", token,
                body("company_id", kumar, "position", "Relief driver", "primary", true));
        post("/api/vehicles/" + companyTruck + "/drivers", token,
                body("user_id", hired, "primary", true));
        post("/api/vehicles/" + companyTruck + "/drivers", token,
                body("user_id", second, "primary", false));

        ResponseEntity<JsonNode> promoted = put(
                "/api/vehicles/" + companyTruck + "/drivers/" + second + "/primary", token, null);

        assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promoted.getBody()).hasSize(2);
        assertThat(promoted.getBody().get(0).get("user_id").asLong()).isEqualTo(second);
        assertThat(promoted.getBody().get(0).get("primary").asBoolean()).isTrue();
        assertThat(promoted.getBody().get(1).get("primary").asBoolean()).isFalse();
    }

    @Test
    void unassigning_a_driver_who_is_not_assigned_is_a_404() {
        assertThat(delete("/api/vehicles/" + companyTruck + "/drivers/" + hired, token)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("assigning the same person twice updates the row rather than conflicting")
    void adding_a_driver_is_idempotent() {
        post("/api/vehicles/" + companyTruck + "/drivers", token,
                body("user_id", hired, "primary", false));

        ResponseEntity<JsonNode> again = post("/api/vehicles/" + companyTruck + "/drivers", token,
                body("user_id", hired, "primary", true));

        // Inherited from JPA: the composite id is assigned, so save() merges. Documented in the
        // OpenAPI description rather than disguised as a 409.
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody()).hasSize(1);
        assertThat(again.getBody().get(0).get("primary").asBoolean()).isTrue();
    }

    @Test
    void the_members_list_shows_who_may_drive() {
        JsonNode members = get("/api/companies/" + kumar + "/members", token).getBody();

        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("name").asText()).isEqualTo("Suresh Patil");
        assertThat(members.get(0).get("position").asText()).isEqualTo("Driver");
    }
}
