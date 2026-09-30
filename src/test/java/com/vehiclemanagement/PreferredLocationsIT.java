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

/**
 * Setting preferred locations at the moment a vehicle or company is created.
 *
 * <p>The rule underneath all of it is that a company's locations belong to the company and every
 * truck it owns inherits them — and, since changeset 009, that a truck may ALSO have its own on
 * top. These tests exist to prove the create paths record what the caller sent rather than
 * quietly dropping it.
 */
class PreferredLocationsIT extends ApiTest {

    @Autowired
    private StateRepository states;
    @Autowired
    private CityRepository cities;
    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;
    private long maharashtra;
    private long gujarat;
    private long nagpur;
    private long pune;
    private long surat;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
        maharashtra = states.findByCode("MH").orElseThrow().getId();
        gujarat = states.findByCode("GJ").orElseThrow().getId();
        nagpur = cities.findByStateIdAndNameKey(maharashtra, "nagpur").orElseThrow().getId();
        pune = cities.findByStateIdAndNameKey(maharashtra, "pune").orElseThrow().getId();
        surat = cities.findByStateIdAndNameKey(gujarat, "surat").orElseThrow().getId();
    }

    private List<Object> threePlaces() {
        return List.of(
                body("state_id", maharashtra, "city_id", nagpur),
                body("state_id", maharashtra, "city_id", pune),
                body("state_id", gujarat, "city_id", null));   // the whole of Gujarat
    }

    // ── companies ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a company can be created with several locations at once")
    void a_company_with_locations() {
        ResponseEntity<JsonNode> created = post("/api/companies", token,
                body("name", "Kumar Roadways", "places", threePlaces()));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode saved = get("/api/companies/" + idOf(created) + "/locations", token).getBody();
        assertThat(saved).hasSize(3);
    }

    @Test
    void a_company_can_still_be_created_with_none() {
        ResponseEntity<JsonNode> created = post("/api/companies", token,
                body("name", "Deccan Movers"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/companies/" + idOf(created) + "/locations", token).getBody())
                .isEmpty();
    }

    @Test
    @DisplayName("a bad location creates no company at all")
    void the_company_and_its_locations_are_one_transaction() {
        // Nagpur is in Maharashtra, not Gujarat.
        ResponseEntity<JsonNode> refused = post("/api/companies", token,
                body("name", "Ghost Transport",
                        "places", List.of(body("state_id", gujarat, "city_id", nagpur))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(get("/api/companies?q=Ghost", token).getBody().get("total").asLong()).isZero();
    }

    // ── vehicles owned by a person ──────────────────────────────────────────

    @Test
    @DisplayName("an owner-driver's truck can be registered with its own route")
    void an_owner_driven_vehicle_with_locations() {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "places", threePlaces(),
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(created);

        JsonNode where = get("/api/vehicles/" + id + "/locations", token).getBody();
        assertThat(where).hasSize(3);
        assertThat(where).allSatisfy(l -> assertThat(l.get("source").asText()).isEqualTo("VEHICLE"));
        assertThat(serving(nagpur)).contains(id);
        assertThat(serving(surat)).contains(id);   // the whole-Gujarat row matches Surat
    }

    // ── vehicles owned by a company ─────────────────────────────────────────

    @Test
    @DisplayName("through intake, a company's truck puts the route on the COMPANY")
    void intake_with_a_company_sets_the_company_route() {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", "MH14CD5678", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "company_name", "Kumar Roadways",
                "places", threePlaces(),
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(created);
        long companyId = created.getBody().get("owner_company_id").asLong();

        // On the company, where the rule says they belong...
        assertThat(get("/api/companies/" + companyId + "/locations", token).getBody()).hasSize(3);
        // ...and inherited by the truck.
        JsonNode where = get("/api/vehicles/" + id + "/locations", token).getBody();
        assertThat(where).hasSize(3);
        assertThat(where).allSatisfy(l -> assertThat(l.get("source").asText()).isEqualTo("COMPANY"));
        assertThat(serving(pune)).contains(id);
    }

    @Test
    @DisplayName("a second truck for the same company inherits the route already set")
    void the_company_route_is_shared() {
        post("/api/vehicles/intake", token, body(
                "registration_number", "MH14CD5678", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "company_name", "Kumar Roadways", "places", threePlaces(),
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        ResponseEntity<JsonNode> second = post("/api/vehicles/intake", token, body(
                "registration_number", "MH14CD9999", "body_type_id", bodyTypeId,
                "driver_name", "Mohammed Irfan", "driver_mobile", "9811008123",
                "company_name", "Kumar Roadways",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/vehicles/" + idOf(second) + "/locations", token).getBody())
                .hasSize(3);
    }

    @Test
    @DisplayName("on the id-based create, a company vehicle KEEPS the places it was given")
    void a_company_vehicle_may_be_given_its_own_route() {
        // This used to be a 422. Refusing was the right behaviour under the old rule -- silently
        // dropping them would have left whoever filled the form believing the truck was routed
        // -- but the rule itself was wrong, so now they are simply kept. See changeset 009.
        long companyId = idOf(post("/api/companies", token, body("name", "Kumar Roadways")));

        ResponseEntity<JsonNode> created = post("/api/vehicles", token, body(
                "registration_number", "MH14CD5678", "body_type_id", bodyTypeId,
                "owner_company_id", companyId,
                "places", threePlaces(),
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        assertThat(get("/api/vehicles/" + id + "/locations", token).getBody()).hasSize(3);
    }

    @Test
    @DisplayName("a bad location leaves no vehicle behind")
    void the_vehicle_and_its_locations_are_one_transaction() {
        ResponseEntity<JsonNode> refused = post("/api/vehicles/intake", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "places", List.of(body("state_id", gujarat, "city_id", nagpur)),
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(get("/api/vehicles?q=PB10", token).getBody().get("total").asLong()).isZero();
        assertThat(get("/api/users?q=Gurpreet", token).getBody().get("total").asLong()).isZero();
    }

    @Test
    void omitting_places_entirely_still_works() {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", "PB10EF9012", "body_type_id", bodyTypeId,
                "driver_name", "Gurpreet Singh", "driver_mobile", "9811008122",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/vehicles/" + idOf(created) + "/locations", token).getBody()).isEmpty();
    }

    private List<Long> serving(long cityId) {
        JsonNode page = get("/api/vehicles?serving_city_id=" + cityId, token).getBody();
        return java.util.stream.StreamSupport.stream(page.get("items").spliterator(), false)
                .map(n -> n.get("id").asLong()).toList();
    }
}
