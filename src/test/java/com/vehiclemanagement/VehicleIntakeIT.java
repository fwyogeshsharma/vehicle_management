package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserRole;
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
 * Registering a truck from a name, a mobile and a company name.
 *
 * <p>This is the path office staff actually use, and it is the only way someone who is not an
 * administrator creates a user record. What matters is that it is all-or-nothing and that it
 * reuses existing records rather than quietly duplicating people.
 */
class VehicleIntakeIT extends ApiTest {

    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
    }

    private ResponseEntity<JsonNode> intake(String reg, String driver, String mobile,
                                            String company) {
        return post("/api/vehicles/intake", token, body(
                "registration_number", reg,
                "body_type_id", bodyTypeId,
                "driver_name", driver,
                "driver_mobile", mobile,
                "company_name", company,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));
    }

    @Test
    @DisplayName("a company truck: company created, driver created, hired, and assigned")
    void the_whole_chain_in_one_call() {
        ResponseEntity<JsonNode> created =
                intake("MH12AB1234", "Suresh Patil", "9811008121", "Kumar Roadways");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long vehicleId = idOf(created);
        assertThat(created.getBody().get("company_owned").asBoolean()).isTrue();

        long companyId = created.getBody().get("owner_company_id").asLong();
        assertThat(get("/api/companies/" + companyId, token).getBody().get("name").asText())
                .isEqualTo("Kumar Roadways");

        JsonNode drivers = get("/api/vehicles/" + vehicleId + "/drivers", token).getBody();
        assertThat(drivers).hasSize(1);
        assertThat(drivers.get(0).get("name").asText()).isEqualTo("Suresh Patil");
        assertThat(drivers.get(0).get("primary").asBoolean()).isTrue();

        // The employment is what made the assignment legal, so it must actually be there.
        long driverId = drivers.get(0).get("user_id").asLong();
        JsonNode memberships = get("/api/users/" + driverId + "/companies", token).getBody();
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).get("company_id").asLong()).isEqualTo(companyId);
    }

    @Test
    @DisplayName("no company name means the driver owns it — the owner-operator")
    void without_a_company_the_driver_owns_it() {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("company_owned").asBoolean()).isFalse();
        assertThat(created.getBody().get("owner_user_id").isNull()).isFalse();

        JsonNode drivers = get("/api/vehicles/" + idOf(created) + "/drivers", token).getBody();
        assertThat(drivers).hasSize(1);
        assertThat(drivers.get(0).get("user_id").asLong())
                .isEqualTo(created.getBody().get("owner_user_id").asLong());
    }

    @Test
    @DisplayName("plate, body type, axles and wheels may all be left out — changeset 023")
    void the_plate_and_dimensions_are_optional() {
        ResponseEntity<JsonNode> first = post("/api/vehicles/intake", token, body(
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "capacity", "16 Ton"));
        // A second plate-less truck: uq_vehicles_reg is NULLS DISTINCT, so this is no conflict.
        ResponseEntity<JsonNode> second = post("/api/vehicles/intake", token, body(
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "capacity", "20 Ton"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = first.getBody();
        assertThat(v.get("registration_number").isNull()).isTrue();
        assertThat(v.get("body_type_id").isNull()).isTrue();
        assertThat(v.get("no_of_axles").isNull()).isTrue();
        assertThat(v.get("no_of_wheels").isNull()).isTrue();
    }

    @Test
    @DisplayName("an optional plate, when given, is still validated")
    void a_given_plate_is_still_checked() {
        ResponseEntity<JsonNode> bad = post("/api/vehicles/intake", token, body(
                "registration_number", "nonsense!!",
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "capacity", "16 Ton"));

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("the same driver on a second truck is reused, not duplicated")
    void an_existing_driver_is_matched_on_the_mobile() {
        intake("MH12AB1234", "Suresh Patil", "9811008121", "Kumar Roadways");
        // Different spelling of the same number, and a slightly different name.
        ResponseEntity<JsonNode> second = intake("MH12AB5678", "S. Patil", "+91 98110 08121",
                "Kumar Roadways");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/users?q=9811008121", token).getBody().get("total").asLong())
                .isEqualTo(1);
        // And the name on file is NOT overwritten by the second, looser spelling.
        assertThat(get("/api/users?q=Suresh", token).getBody().get("total").asLong()).isEqualTo(1);
        assertThat(get("/api/companies?q=Kumar", token).getBody().get("total").asLong())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an existing company is matched on its name, whatever the case")
    void an_existing_company_is_reused() {
        post("/api/companies", token, body("name", "Patel Freight Lines"));

        intake("GJ01XY4455", "Mohammed Irfan", "9811008123", "patel FREIGHT lines");

        assertThat(get("/api/companies?q=Patel", token).getBody().get("total").asLong())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a bad plate creates neither the driver nor the company")
    void it_is_all_or_nothing() {
        ResponseEntity<JsonNode> refused =
                intake("not a plate!!", "Ghost Driver", "9811008199", "Ghost Transport");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The whole point of doing this in one transaction: a failed registration must not leave
        // an orphan driver and an orphan company behind for someone to clean up.
        assertThat(get("/api/users?q=Ghost", token).getBody().get("total").asLong()).isZero();
        assertThat(get("/api/companies?q=Ghost", token).getBody().get("total").asLong()).isZero();
    }

    @Test
    void a_bad_mobile_is_keyed_to_its_own_field() {
        ResponseEntity<JsonNode> refused =
                intake("MH12AB1234", "Suresh Patil", "12345", "Kumar Roadways");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("detail").get(0).get("loc").toString())
                .isEqualTo("[\"body\",\"driver_mobile\"]");
    }

    @Test
    void a_duplicate_plate_is_still_refused() {
        intake("MH12AB1234", "Suresh Patil", "9811008121", "Kumar Roadways");

        assertThat(intake("MH12AB1234", "Someone Else", "9811008122", "Kumar Roadways")
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a deactivated driver is refused rather than silently resurrected")
    void a_deactivated_driver_stops_the_intake() {
        long id = users.create("Retired Hand", "9811008188", UserType.DRIVER).getId();
        users.deactivate(id);

        ResponseEntity<JsonNode> refused =
                intake("MH12AB9999", "Retired Hand", "9811008188", "Kumar Roadways");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("deactivated");
    }

    @Test
    @DisplayName("the driver created this way has no login, and cannot be given one")
    void an_intake_driver_never_signs_in() {
        intake("MH12AB1234", "Suresh Patil", "9811008121", "Kumar Roadways");
        long driverId = get("/api/vehicles/1/drivers", token).getBody().get(0).get("user_id")
                .asLong();

        assertThat(get("/api/users/" + driverId, token).getBody().get("can_sign_in").asBoolean())
                .isFalse();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> users.setLogin(driverId, "suresh", "a-good-password", UserRole.CSR))
                .hasMessageContaining("does not sign in");
    }

    @Test
    @DisplayName("the vehicle list carries a contact number for each row")
    void the_list_shows_who_to_ring() {
        intake("MH12AB1234", "Suresh Patil", "9811008121", "Kumar Roadways");
        post("/api/vehicles/intake", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        JsonNode rows = get("/api/vehicles?sort=registration_number", token).getBody().get("items");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("contact_name").asText()).isEqualTo("Suresh Patil");
        assertThat(rows.get(0).get("contact_mobile").asText()).isEqualTo("9811008121");
        assertThat(rows.get(0).get("contact_role").asText()).isEqualTo("DRIVER");
        assertThat(rows.get(1).get("contact_mobile").asText()).isEqualTo("9811008122");
    }

    @Test
    @DisplayName("a company truck with no driver falls back to the company's number")
    void the_contact_falls_back_down_the_chain() {
        long companyId = idOf(post("/api/companies", token, body("name", "Deccan Movers")));
        put("/api/companies/" + companyId, token,
                body("name", "Deccan Movers", "mobile", "9811005000"));
        post("/api/vehicles", token, body(
                "registration_number", "KA05MN3322", "body_type_id", bodyTypeId,
                "owner_company_id", companyId,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        JsonNode row = get("/api/vehicles?q=KA05", token).getBody().get("items").get(0);

        assertThat(row.get("contact_name").asText()).isEqualTo("Deccan Movers");
        assertThat(row.get("contact_mobile").asText()).isEqualTo("9811005000");
        assertThat(row.get("contact_role").asText()).isEqualTo("COMPANY");
    }
}
