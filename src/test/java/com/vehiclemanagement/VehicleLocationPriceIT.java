package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.StateRepository;
import com.vehiclemanagement.service.BodyTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a driver quotes to run a truck to each place: a TOTAL, held on the vehicle's own location
 * row. Optional everywhere, never computed, never shared with the company.
 */
class VehicleLocationPriceIT extends ApiTest {

    @Autowired
    private StateRepository states;
    @Autowired
    private CityRepository cities;
    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;
    private long maharashtra;
    private long nagpur;
    private long pune;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
        maharashtra = states.findByCode("MH").orElseThrow().getId();
        nagpur = cities.findByStateIdAndNameKey(maharashtra, "nagpur").orElseThrow().getId();
        pune = cities.findByStateIdAndNameKey(maharashtra, "pune").orElseThrow().getId();
    }

    private long registerOwnerDriver(String plate, String mobile, Object places) {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", plate, "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", mobile,
                "capacity", "16 Ton", "places", places));
        assertThat(created.getStatusCode()).as("%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return idOf(created);
    }

    private JsonNode locationOf(long vehicleId, long cityId) {
        for (JsonNode l : get("/api/vehicles/" + vehicleId + "/locations", token).getBody()) {
            if (l.get("city_id").asLong() == cityId) {
                return l;
            }
        }
        throw new AssertionError("no location for city " + cityId);
    }

    @Test
    @DisplayName("each place keeps its own quoted total")
    void a_price_per_place() {
        long id = registerOwnerDriver("MH12AB1001", "9811008201", List.of(
                body("state_id", maharashtra, "city_id", nagpur, "price", 42000),
                body("state_id", maharashtra, "city_id", pune, "price", 18500.50)));

        assertThat(locationOf(id, nagpur).get("price").decimalValue()).isEqualByComparingTo("42000");
        assertThat(locationOf(id, pune).get("price").decimalValue()).isEqualByComparingTo("18500.50");
    }

    @Test
    @DisplayName("a place with no price is fine and reads back as null")
    void price_is_optional() {
        long id = registerOwnerDriver("MH12AB1002", "9811008202", List.of(
                body("state_id", maharashtra, "city_id", nagpur)));

        assertThat(locationOf(id, nagpur).get("price").isNull()).isTrue();
    }

    @Test
    @DisplayName("replacing the locations replaces the prices with them")
    void put_replaces_the_prices() {
        long id = registerOwnerDriver("MH12AB1003", "9811008203", List.of(
                body("state_id", maharashtra, "city_id", nagpur, "price", 42000)));

        put("/api/vehicles/" + id + "/locations", token, body("places", List.of(
                body("state_id", maharashtra, "city_id", nagpur, "price", 39000))));

        assertThat(locationOf(id, nagpur).get("price").decimalValue()).isEqualByComparingTo("39000");
    }

    @Test
    @DisplayName("a negative price is refused and nothing is saved")
    void negative_is_refused() {
        ResponseEntity<JsonNode> refused = post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB1004", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008204",
                "capacity", "16 Ton", "places", List.of(
                        body("state_id", maharashtra, "city_id", nagpur, "price", -5))));

        assertThat(refused.getStatusCode().is4xxClientError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Long.class)).isZero();
    }

    @Test
    @DisplayName("the database refuses a negative price whoever writes it")
    void the_schema_refuses_a_negative_price() {
        long id = registerOwnerDriver("MH12AB1005", "9811008205", List.of(
                body("state_id", maharashtra, "city_id", nagpur)));

        assertThat(catchThrowable(() -> jdbc.update(
                "UPDATE vehicle_x_location SET price = -1 WHERE vehicle_id = ?", id)))
                .hasMessageContaining("ck_vxl_price");
    }

    @Test
    @DisplayName("a company's truck keeps the quote on its own row, not the shared company one")
    void a_company_truck_keeps_the_quote_on_the_truck() {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB1006", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008206",
                "company_name", "Kumar Roadways", "capacity", "16 Ton",
                "places", List.of(
                        body("state_id", maharashtra, "city_id", nagpur, "price", 42000),
                        body("state_id", maharashtra, "city_id", pune))));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(created);

        // The company gets both places, unpriced -- every truck it owns inherits them.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_x_location", Long.class)).isEqualTo(2);
        // The truck alone carries the one that was quoted.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_x_location WHERE vehicle_id = ?", Long.class, id))
                .isEqualTo(1);
        boolean found = false;
        for (JsonNode l : get("/api/vehicles/" + id + "/locations", token).getBody()) {
            if ("VEHICLE".equals(l.get("source").asText())) {
                assertThat(l.get("price").decimalValue()).isEqualByComparingTo("42000");
                found = true;
            } else {
                assertThat(l.get("price").isNull()).isTrue();
            }
        }
        assertThat(found).isTrue();
    }
}
