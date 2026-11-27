package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A refused value is reported against its field, naming the field and what was sent. */
class ValidationMessagesIT extends ApiTest {

    private String admin;

    @BeforeEach
    void reset() {
        resetDomainData();
        admin = admin();
    }

    private void refused(String path, Map<String, Object> request, String field, String message) {
        ResponseEntity<JsonNode> r = post(path, admin, request);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode error = r.getBody().get("detail").get(0);
        assertThat(error.get("loc").get(error.get("loc").size() - 1).asText()).isEqualTo(field);
        assertThat(error.get("msg").asText()).isEqualTo(message);
    }

    @Test
    @DisplayName("a rule in a service quotes the value that broke it")
    void service_rules_quote_the_value() {
        refused("/api/users", body("name", "Suresh", "mobile", "12345", "user_type", "DRIVER"),
                "mobile", "Mobile \"12345\": Enter a valid 10-digit Indian mobile number"
                        + " (starting with 6-9).");
        refused("/api/vehicles/intake", body("driver_name", "Suresh", "driver_mobile", "98x",
                        "capacity", "16 Ton"),
                "driver_mobile", "Driver mobile \"98x\": Enter a valid 10-digit Indian mobile"
                        + " number (starting with 6-9).");
    }

    @Test
    @DisplayName("a missing value says which field is required")
    void missing_values_name_the_field() {
        refused("/api/users", body("name", " ", "mobile", "9811100009", "user_type", "DRIVER"),
                "name", "Name is required.");
    }

    @Test
    @DisplayName("too long, and too large, are field errors rather than server errors")
    void limits_are_field_errors() {
        String longName = "x".repeat(121);
        refused("/api/users", body("name", longName, "mobile", "9811100009",
                        "user_type", "DRIVER"),
                "name", "Name \"" + "x".repeat(40) + "\u2026\" is too long: 121 characters,"
                        + " at most 120.");
        refused("/api/capacities", body("label", "Huge", "tons", 123456),
                "tons", "Tonnage \"123456\" is too large: at most 9999.99.");
    }

    @Test
    @DisplayName("a value of the wrong kind names the field instead of 'could not be read'")
    void wrong_kinds_name_the_field() {
        refused("/api/vehicles/intake", body("driver_mobile", "9811100009",
                        "no_of_axles", "two"),
                "no_of_axles", "Axles \"two\" is not a valid number.");
        refused("/api/vehicles/intake", body("driver_mobile", "9811100009",
                        "no_of_axles", 99999),
                "no_of_axles", "Axles is out of range.");
        refused("/api/users", body("name", "Suresh", "mobile", "9811100009",
                        "user_type", "PILOT"),
                "user_type", "User type \"PILOT\" is not one of: "
                        + String.join(", ", java.util.Arrays.stream(
                        com.vehiclemanagement.domain.UserType.values())
                        .map(Enum::name).toList()) + ".");
    }

    @Test
    @DisplayName("a customer's bad GSTIN or a negative package count is a field error")
    void database_rules_are_field_errors() {
        refused("/api/customers", body("name", "Zed Traders", "gstin", "BADGSTIN"),
                "gstin", "GSTIN \"BADGSTIN\": That does not look like a GSTIN,"
                        + " e.g. 27AAPFU0939F1ZV.");
        ResponseEntity<JsonNode> r = post("/api/lr", admin,
                body("lr_date", "2026-11-27", "packages", -3));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().toString()).contains("packages").contains("-3");
    }

    @Test
    @DisplayName("a short value is still named, even when the message happens to contain it")
    void short_values_are_named() {
        refused("/api/customers", body("name", "Yash Traders", "mobile", "1"),
                "mobile", "Mobile \"1\": Enter a valid 10-digit Indian mobile number"
                        + " (starting with 6-9).");
    }

    @Test
    @DisplayName("a round number is shown as typed, not in scientific notation")
    void numbers_are_shown_plainly() {
        refused("/api/lr", body("lr_date", "2026-11-27", "consignor_name", "A",
                        "consignee_name", "B", "from_place", "Jaipur", "to_place", "Pune",
                        "freight_charges", 10, "advance", 500),
                "advance", "Advance \"500\": The advance is more than the total charges.");
    }

    @Test
    @DisplayName("receipt charges name their own box, and an impossible total is refused")
    void receipt_charges() {
        refused("/api/lr", body("lr_date", "2026-11-27", "consignor_name", "A",
                        "consignee_name", "B", "from_place", "Jaipur", "to_place", "Pune",
                        "loading_charges", -4),
                "loading_charges", "Loading charges \"-4\": This cannot be negative.");
        refused("/api/lr", body("lr_date", "2026-11-27", "consignor_name", "A",
                        "consignee_name", "B", "from_place", "Jaipur", "to_place", "Pune",
                        "freight_charges", 9999999999L, "loading_charges", 9999999999L),
                "total_charges", "The charges add up to more than 9999999999.99.");
    }

    @Test
    @DisplayName("an unknown state or truck type is a field error, never a server error")
    void unknown_ids_are_field_errors() {
        List<Map<String, Object>> nowhere = List.of(body("state_id", 999999));
        refused("/api/companies", body("name", "Nowhere Carriers", "places", nowhere),
                "state_id", "That state does not exist.");
        refused("/api/vehicles/intake", body("driver_mobile", "9876500011", "capacity", "9 MT",
                        "places", nowhere),
                "state_id", "That state does not exist.");
        refused("/api/vehicles/intake", body("driver_mobile", "9876500012", "capacity", "9 MT",
                        "company_name", "Far Carriers", "places", nowhere),
                "state_id", "That state does not exist.");
        refused("/api/lanes", body("from_place", "Jaipur", "to_place", "Pune", "goods", "Salt",
                        "body_type_id", 999999),
                "body_type_id", "That truck type is not on the list.");
        refused("/api/lr", body("lr_date", "2026-11-27", "consignor_name", "A",
                        "consignee_name", "B", "from_place", "Jaipur", "to_place", "Pune",
                        "body_type_id", 999999),
                "body_type_id", "That truck type is not on the list.");
    }

    @Test
    @DisplayName("a password is never repeated back")
    void passwords_are_not_quoted() {
        ResponseEntity<JsonNode> r = post("/api/auth/change-password", admin,
                body("current_password", PASSWORD, "new_password", "zq9wk"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().toString()).contains("at least 8").doesNotContain("zq9wk");
        assertThat(post("/api/auth/login", null, body("username", "root", "password", " "))
                .getBody().toString()).contains("Password is required.");
    }
}
