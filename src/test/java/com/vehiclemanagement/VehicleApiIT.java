package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserType;
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

/**
 * The fleet, over HTTP.
 *
 * <p>The refusals matter as much as the successes here. Several of these rules live in the
 * database as constraints, and the question this suite answers is whether they arrive at the
 * client as a readable 409 or as a 500 with PostgreSQL's own words in it.
 */
class VehicleApiIT extends ApiTest {

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
    private long driverId;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
        maharashtra = states.findByCode("MH").orElseThrow().getId();
        nagpur = cities.findByStateIdAndNameKey(maharashtra, "nagpur").orElseThrow().getId();
        pune = cities.findByStateIdAndNameKey(maharashtra, "pune").orElseThrow().getId();
        driverId = users.create("Ramesh Kumar", "9811008120", UserType.BOTH).getId();
    }

    private long registerOwnerDriven() {
        ResponseEntity<JsonNode> created = post("/api/vehicles", token, body(
                "registration_number", "MH12AB1234",
                "body_type_id", bodyTypeId,
                "owner_user_id", driverId,
                "owner_also_drives", true,
                "no_of_axles", 2,
                "no_of_wheels", 6,
                "capacity", "16 Ton",
                "length_ft", 22));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(created);
    }

    @Test
    void register_read_edit_and_retire() {
        long id = registerOwnerDriven();

        JsonNode detail = get("/api/vehicles/" + id, token).getBody();
        assertThat(detail.get("registration_number").asText()).isEqualTo("MH12AB1234");
        // capacity_tons is derived by the database from the free text, never sent by the client
        assertThat(detail.get("capacity_tons").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("16.00"));
        assertThat(detail.get("company_owned").asBoolean()).isFalse();

        ResponseEntity<JsonNode> edited = put("/api/vehicles/" + id, token, body(
                "body_type_id", bodyTypeId, "no_of_axles", 3, "no_of_wheels", 10,
                "capacity", "25 Ton", "length_ft", 32, "notes", "repainted"));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("capacity_tons").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("25.00"));

        assertThat(delete("/api/vehicles/" + id, token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // DELETE deactivates; the row is still there and still readable
        assertThat(get("/api/vehicles/" + id, token).getBody().get("active").asBoolean()).isFalse();
        assertThat(post("/api/vehicles/" + id + "/restore", token, null)
                .getBody().get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an unregistered vehicle is a 404 with a readable message, not a stack trace")
    void a_missing_vehicle_is_a_clean_404() {
        ResponseEntity<JsonNode> missing = get("/api/vehicles/999999", token);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(detail(missing)).contains("not found");
    }

    @Test
    @DisplayName("a path that maps to nothing is a 404, not a 500")
    void an_unmapped_path_is_a_clean_404() {
        // A client with a typo in a URL must be told the path does not exist. Reported as a 500
        // it looks like a server fault and sends whoever is debugging it to the wrong place --
        // which is precisely what happened to this project's own UI.
        ResponseEntity<JsonNode> nonsense = get("/api/vehicles/1/passengers", token);

        assertThat(nonsense.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a field error names the snake_case field the client sent")
    void a_bad_registration_number_is_keyed_to_its_input() {
        ResponseEntity<JsonNode> refused = post("/api/vehicles", token, body(
                "registration_number", "nonsense!!",
                "body_type_id", bodyTypeId, "owner_user_id", driverId,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode first = refused.getBody().get("detail").get(0);
        assertThat(first.get("loc").toString()).isEqualTo("[\"body\",\"registration_number\"]");
        assertThat(first.get("msg").asText()).isNotBlank();
    }

    @Test
    void two_owners_or_none_are_both_refused() {
        long companyId = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));

        assertThat(post("/api/vehicles", token, body(
                "registration_number", "MH12AB1111", "body_type_id", bodyTypeId,
                "owner_user_id", driverId, "owner_company_id", companyId,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(post("/api/vehicles", token, body(
                "registration_number", "MH12AB2222", "body_type_id", bodyTypeId,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("selling a vehicle reports the drivers it displaced, and empties the cab list")
    void selling_clears_the_drivers_and_says_so() {
        long id = registerOwnerDriven();
        assertThat(get("/api/vehicles/" + id + "/drivers", token).getBody()).hasSize(1);

        long companyId = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));
        ResponseEntity<JsonNode> sold = put("/api/vehicles/" + id + "/owner", token,
                body("company_id", companyId));

        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sold.getBody().get("removed_drivers").asInt()).isEqualTo(1);
        assertThat(sold.getBody().get("vehicle").get("company_owned").asBoolean()).isTrue();
        assertThat(get("/api/vehicles/" + id + "/drivers", token).getBody()).isEmpty();
    }

    @Test
    @DisplayName("a company-owned vehicle may hold locations of its own, on top of the company's")
    void a_company_vehicle_can_have_its_own_route() {
        // Reversed by changeset 009. The old rule said a company's truck runs where the company
        // runs and nowhere else, which made the ordinary case -- a fleet covering Maharashtra
        // with one truck dedicated to a Nagpur shuttle -- unsayable except by rerouting the
        // whole company.
        long id = registerOwnerDriven();
        long companyId = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));
        put("/api/companies/" + companyId + "/locations", token,
                body("places", List.of(body("state_id", maharashtra, "city_id", null))));
        put("/api/vehicles/" + id + "/owner", token, body("company_id", companyId));

        ResponseEntity<JsonNode> set = put("/api/vehicles/" + id + "/locations", token,
                body("places", List.of(body("state_id", maharashtra, "city_id", nagpur))));

        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Additive, not a replacement: the effective set carries both, and says which is which.
        JsonNode effective = get("/api/vehicles/" + id + "/locations", token).getBody();
        assertThat(effective).hasSize(2);
        assertThat(effective.findValuesAsText("source"))
                .containsExactlyInAnyOrder("COMPANY", "VEHICLE");
    }

    @Test
    @DisplayName("a city in the wrong state is refused before it reaches the database")
    void a_city_must_be_in_the_state_beside_it() {
        long id = registerOwnerDriven();
        long gujarat = states.findByCode("GJ").orElseThrow().getId();

        ResponseEntity<JsonNode> refused = put("/api/vehicles/" + id + "/locations", token,
                body("places", List.of(body("state_id", gujarat, "city_id", nagpur))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(detail(refused)).contains("not in that state");
    }

    @Test
    @DisplayName("a whole-state preference matches every city in that state")
    void serving_a_city_and_the_effective_locations_agree() {
        long id = registerOwnerDriven();
        put("/api/vehicles/" + id + "/locations", token,
                body("places", List.of(body("state_id", maharashtra))));

        JsonNode effective = get("/api/vehicles/" + id + "/locations", token).getBody();
        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).get("source").asText()).isEqualTo("VEHICLE");
        assertThat(effective.get(0).get("city_id").isNull()).isTrue();

        // Both Nagpur and Pune are in Maharashtra, so the state-only row matches both.
        assertThat(servingCity(nagpur)).contains(id);
        assertThat(servingCity(pune)).contains(id);
    }

    @Test
    @DisplayName("a company with no locations means its vehicle serves nowhere")
    void a_company_without_locations_does_not_fall_back() {
        long id = registerOwnerDriven();
        put("/api/vehicles/" + id + "/locations", token,
                body("places", List.of(body("state_id", maharashtra, "city_id", nagpur))));
        assertThat(servingCity(nagpur)).contains(id);

        long companyId = idOf(post("/api/companies", token, body("name", "Deccan Movers")));
        put("/api/vehicles/" + id + "/owner", token, body("company_id", companyId));

        // Its own rows are gone, and the company has none -- so nowhere, not "back to its own".
        assertThat(get("/api/vehicles/" + id + "/locations", token).getBody()).isEmpty();
        assertThat(servingCity(nagpur)).doesNotContain(id);
    }

    @Test
    @DisplayName("once a company owns it, the company's locations apply")
    void a_company_vehicle_inherits_the_company_locations() {
        long id = registerOwnerDriven();
        long companyId = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));
        put("/api/companies/" + companyId + "/locations", token,
                body("places", List.of(body("state_id", maharashtra))));
        put("/api/vehicles/" + id + "/owner", token, body("company_id", companyId));

        JsonNode effective = get("/api/vehicles/" + id + "/locations", token).getBody();

        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).get("source").asText()).isEqualTo("COMPANY");
        assertThat(servingCity(pune)).contains(id);
    }

    @Test
    @DisplayName("serving_city_id is a location filter; activity is a separate one")
    void an_inactive_vehicle_is_excluded_only_when_asked() {
        long id = registerOwnerDriven();
        put("/api/vehicles/" + id + "/locations", token,
                body("places", List.of(body("state_id", maharashtra, "city_id", nagpur))));
        assertThat(servingCity(nagpur)).contains(id);

        delete("/api/vehicles/" + id, token);

        // The filters are orthogonal on purpose -- folding an implicit activity check into a
        // location filter is the kind of hidden behaviour that makes a list endpoint untrustworthy.
        // A caller asking "what can I dispatch?" adds active=true, and the endpoint says so.
        assertThat(servingCity(nagpur)).contains(id);
        assertThat(servingCityAndActive(nagpur)).doesNotContain(id);
    }

    private List<Long> servingCity(long cityId) {
        return ids(get("/api/vehicles?serving_city_id=" + cityId, token).getBody());
    }

    private List<Long> servingCityAndActive(long cityId) {
        return ids(get("/api/vehicles?serving_city_id=" + cityId + "&active=true", token).getBody());
    }

    private static List<Long> ids(JsonNode page) {
        return java.util.stream.StreamSupport
                .stream(page.get("items").spliterator(), false)
                .map(n -> n.get("id").asLong())
                .toList();
    }

    @Test
    @DisplayName("length is optional; the other three dimensions are not")
    void length_is_optional() {
        // The column has always been nullable and ck_vehicles_length only bounds it when
        // present. Only the Java validator insisted, and a body length is the one dimension
        // nobody reads off the truck at the roadside.
        ResponseEntity<JsonNode> created = post("/api/vehicles", token, body(
                "registration_number", "MH12ZZ9090",
                "body_type_id", bodyTypeId,
                "owner_user_id", driverId,
                "owner_also_drives", true,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("length_ft").isNull()).isTrue();

        // Axles, wheels and capacity still are required -- this loosened one field, not four.
        for (String missing : java.util.List.of("no_of_axles", "no_of_wheels", "capacity")) {
            java.util.Map<String, Object> request = body(
                    "registration_number", "MH12ZZ9091",
                    "body_type_id", bodyTypeId,
                    "owner_user_id", driverId,
                    "owner_also_drives", true,
                    "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton");
            request.remove(missing);
            assertThat(post("/api/vehicles", token, request).getStatusCode())
                    .as("without %s", missing).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("a length outside the physical range is still refused")
    void an_absurd_length_is_still_refused() {
        java.util.Map<String, Object> request = body(
                "registration_number", "MH12ZZ9092",
                "body_type_id", bodyTypeId,
                "owner_user_id", driverId,
                "owner_also_drives", true,
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton",
                "length_ft", 400);

        // Optional does not mean unchecked: ck_vehicles_length bounds it at 4-80 feet.
        assertThat(post("/api/vehicles", token, request).getStatusCode())
                .isNotEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("the plate search matches anywhere in the number, and is index-backed")
    void registration_search_is_a_contains_match() {
        registerOwnerDriven();

        // A dispatcher reading the last four digits off a photograph does not know the state
        // code, which is why this is a contains-match and why changeset 031 puts a trigram
        // index behind it -- a leading wildcard cannot use the btree from uq_vehicles_reg.
        assertThat(get("/api/vehicles?q=AB1234", token).getBody().get("total").asInt())
                .isEqualTo(1);
        assertThat(get("/api/vehicles?q=1234", token).getBody().get("total").asInt())
                .isEqualTo(1);
        assertThat(get("/api/vehicles?q=9999", token).getBody().get("total").asInt()).isZero();
    }

    @Test
    @DisplayName("the trigram index exists and is the one that serves the search")
    void the_search_index_is_wired_up() {
        // Asserted against the catalogue rather than a timing, which would be flaky: the
        // point is that the changeset created the thing, so a future migration that drops
        // pg_trgm fails here rather than quietly reintroducing a table scan.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE tablename = 'vehicles' AND indexname = 'idx_vehicles_reg_trgm'
                """, String.class))
                .contains("gin").contains("registration_number").contains("gin_trgm_ops");
    }
}
