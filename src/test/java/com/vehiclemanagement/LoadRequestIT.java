package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.StateRepository;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.ConsignorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The day's load requests: cargo customers ask us to move. The customer is picked from the
 * master by id; the customer and the route are all that is required.
 */
class LoadRequestIT extends ApiTest {

    private static final String PATH = "/api/load-requests";

    @Autowired
    private ConsignorService customers;
    @Autowired
    private StateRepository states;
    @Autowired
    private CityRepository cities;
    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long kumar;
    private long patel;

    @BeforeEach
    void reset() {
        resetDomainData();
        jdbc.update("DELETE FROM load_requests");
        // Like lorry receipts and contracts: ADMIN or TEJJJ_CSR, so work as an administrator.
        token = admin();
        kumar = customers.create("Kumar Cement", null, null, null).getId();
        patel = customers.create("Patel Steel", null, null, null).getId();
    }

    private Map<String, Object> minimal(long customer) {
        return body("consignor_id", customer, "from_place", "Jaipur", "to_place", "Surat");
    }

    private Map<String, Object> full() {
        Map<String, Object> r = minimal(kumar);
        r.put("request_date", "2026-10-29");
        r.put("goods", "Cement");
        r.put("weight_tons", 75);
        r.put("trucks_required", 3);
        r.put("loading_date", "2026-10-31");
        r.put("offered_price", 52000);
        r.put("contact_name", "Anil Kumar");
        r.put("contact_mobile", "9812345678");
        r.put("notes", "Bagged, needs tarpaulin");
        return r;
    }

    private long record(Map<String, Object> request) {
        ResponseEntity<JsonNode> response = post(PATH, token, request);
        assertThat(response.getStatusCode()).as("%s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    @Test
    @DisplayName("the customer and the route are enough; the day is today and it starts OPEN")
    void the_minimum() {
        JsonNode saved = post(PATH, token, minimal(kumar)).getBody();

        assertThat(saved.get("consignor_id").asLong()).isEqualTo(kumar);
        assertThat(saved.get("company_name").asText()).isEqualTo("Kumar Cement");
        assertThat(saved.get("from_place").asText()).isEqualTo("Jaipur");
        assertThat(saved.get("to_place").asText()).isEqualTo("Surat");
        assertThat(saved.get("request_date").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(saved.get("status").asText()).isEqualTo("OPEN");
        assertThat(saved.get("trucks_required").asInt()).isEqualTo(1);
        assertThat(saved.get("weight_tons").isNull()).isTrue();
        assertThat(saved.get("recorded_by").asText()).isEqualTo("root");
        // Jaipur and Surat are on the city list, so they are linked as well as kept as text.
        assertThat(saved.get("from_city_id").isNull()).isFalse();
    }

    @Test
    @DisplayName("everything given is stored as entered")
    void stores_the_lot() {
        JsonNode saved = post(PATH, token, full()).getBody();

        assertThat(saved.get("request_date").asText()).isEqualTo("2026-10-29");
        assertThat(saved.get("goods").asText()).isEqualTo("Cement");
        assertThat(saved.get("weight_tons").decimalValue()).isEqualByComparingTo("75");
        assertThat(saved.get("trucks_required").asInt()).isEqualTo(3);
        assertThat(saved.get("loading_date").asText()).isEqualTo("2026-10-31");
        assertThat(saved.get("offered_price").decimalValue()).isEqualByComparingTo("52000");
        assertThat(saved.get("contact_name").asText()).isEqualTo("Anil Kumar");
        assertThat(saved.get("contact_mobile").asText()).isEqualTo("9812345678");
    }

    @Test
    @DisplayName("a customer from the master and a route are required")
    void required_fields() {
        assertThat(post(PATH, token, body("from_place", "Jaipur", "to_place", "Surat"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(PATH, token, body("consignor_id", 999999, "from_place", "Jaipur",
                "to_place", "Surat")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(PATH, token, body("consignor_id", kumar, "to_place", "Surat"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(PATH, token, body("consignor_id", kumar, "from_place", "Jaipur"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_requests", Long.class)).isZero();
    }

    @Test
    @DisplayName("the name comes from the master, whatever the request says")
    void name_from_master() {
        Map<String, Object> r = minimal(kumar);
        r.put("company_name", "Somebody Else");

        assertThat(post(PATH, token, r).getBody().get("company_name").asText())
                .isEqualTo("Kumar Cement");
    }

    @Test
    @DisplayName("a place that is not a city is kept as text")
    void unknown_place() {
        JsonNode saved = post(PATH, token, body("consignor_id", kumar,
                "from_place", "Kashipur industrial area", "to_place", "Surat")).getBody();

        assertThat(saved.get("from_place").asText()).isEqualTo("Kashipur industrial area");
        assertThat(saved.get("from_city_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("impossible figures are refused and nothing is saved")
    void impossible_figures() {
        for (Map.Entry<String, Object> bad : Map.<String, Object>of(
                "weight_tons", 0, "trucks_required", 0, "offered_price", -1,
                "contact_mobile", "12345").entrySet()) {
            Map<String, Object> r = minimal(kumar);
            r.put(bad.getKey(), bad.getValue());
            assertThat(post(PATH, token, r).getStatusCode().is4xxClientError())
                    .as(bad.getKey()).isTrue();
        }
        Map<String, Object> status = minimal(kumar);
        status.put("status", "WHATEVER");
        assertThat(post(PATH, token, status).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_requests", Long.class)).isZero();
    }

    @Test
    @DisplayName("an edit replaces the fields and can close the request")
    void edit_and_status() {
        long id = record(full());

        Map<String, Object> edited = full();
        edited.put("trucks_required", 2);
        edited.put("status", "FULFILLED");
        edited.remove("offered_price");
        JsonNode updated = put(PATH + "/" + id, token, edited).getBody();

        assertThat(updated.get("trucks_required").asInt()).isEqualTo(2);
        assertThat(updated.get("status").asText()).isEqualTo("FULFILLED");
        assertThat(updated.get("offered_price").isNull()).isTrue();

        // A later edit that says nothing about status keeps it.
        Map<String, Object> again = full();
        assertThat(put(PATH + "/" + id, token, again).getBody().get("status").asText())
                .isEqualTo("FULFILLED");
    }

    @Test
    @DisplayName("the board filters by day, status, customer and text")
    void the_board() {
        Map<String, Object> today = minimal(kumar);
        today.put("request_date", "2026-10-29");
        record(today);
        Map<String, Object> yesterday = minimal(patel);
        yesterday.put("request_date", "2026-10-28");
        yesterday.put("to_place", "Nagpur");
        long cancelled = record(yesterday);
        Map<String, Object> close = new java.util.HashMap<>(yesterday);
        close.put("status", "CANCELLED");
        put(PATH + "/" + cancelled, token, close);

        assertThat(total("")).isEqualTo(2);
        assertThat(total("?date_from=2026-10-29&date_to=2026-10-29")).isEqualTo(1);
        assertThat(total("?date_from=2026-10-28&date_to=2026-10-29")).isEqualTo(2);
        assertThat(total("?status=CANCELLED")).isEqualTo(1);
        assertThat(total("?status=OPEN")).isEqualTo(1);
        assertThat(total("?consignor_id=" + patel)).isEqualTo(1);
        assertThat(total("?q=nagpur")).isEqualTo(1);
        assertThat(total("?q=kumar")).isEqualTo(1);
        assertThat(total("?q=zzz")).isZero();
    }

    private long total(String query) {
        return get(PATH + query, token).getBody().get("total").asLong();
    }

    private void truck(String plate, String mobile, List<Object> places) {
        ResponseEntity<JsonNode> created = post("/api/vehicles/intake", token, body(
                "registration_number", plate, "body_type_id", bodyTypes.ensure("Open body").getId(),
                "driver_name", "Driver " + plate, "driver_mobile", mobile,
                "capacity", "16 Ton", "places", places));
        assertThat(created.getStatusCode()).as("%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private List<String> plates(String path) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : get(path, token).getBody().get("vehicles")) {
            out.add(v.get("registration_number").asText());
        }
        return out;
    }

    @Test
    @DisplayName("matching vehicles: both places, or either, by the Vehicles tab's own rule")
    void vehicles_that_run_there() {
        long rj = states.findByCode("RJ").orElseThrow().getId();
        long gj = states.findByCode("GJ").orElseThrow().getId();
        long jaipur = cities.findByStateIdAndNameKey(rj, "jaipur").orElseThrow().getId();
        long surat = cities.findByStateIdAndNameKey(gj, "surat").orElseThrow().getId();
        truck("RJ14AA0001", "9811008301", List.of(
                body("state_id", rj, "city_id", jaipur), body("state_id", gj, "city_id", surat)));
        truck("RJ14AA0002", "9811008302", List.of(body("state_id", rj, "city_id", jaipur)));
        // A whole state matches every city in it, so this one covers Surat.
        truck("GJ05AA0003", "9811008303", List.of(body("state_id", gj, "city_id", null)));
        truck("MH12AA0004", "9811008304", List.of());

        long id = record(minimal(kumar));   // Jaipur -> Surat

        assertThat(plates(PATH + "/" + id + "/vehicles")).containsExactly("RJ14AA0001");
        assertThat(plates(PATH + "/" + id + "/vehicles?match=either"))
                .containsExactly("GJ05AA0003", "RJ14AA0001", "RJ14AA0002");
    }

    @Test
    @DisplayName("a place that is not on the city list cannot be matched, and the answer says so")
    void an_unmatched_place() {
        long rj = states.findByCode("RJ").orElseThrow().getId();
        long jaipur = cities.findByStateIdAndNameKey(rj, "jaipur").orElseThrow().getId();
        truck("RJ14AA0009", "9811008309", List.of(body("state_id", rj, "city_id", jaipur)));
        long id = record(body("consignor_id", kumar, "from_place", "Jaipur",
                "to_place", "Kashipur industrial area"));

        JsonNode both = get(PATH + "/" + id + "/vehicles", token).getBody();
        assertThat(both.get("to_matchable").asBoolean()).isFalse();
        assertThat(both.get("from_matchable").asBoolean()).isTrue();
        assertThat(both.get("vehicles")).isEmpty();
        // "Either" leaves the unknown place out and still answers for the one it can.
        assertThat(plates(PATH + "/" + id + "/vehicles?match=either"))
                .containsExactly("RJ14AA0009");
    }

    @Test
    @DisplayName("a plain CSR cannot read or write load requests")
    void a_plain_csr_is_refused() {
        String csr = staff();

        assertThat(get(PATH, csr).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(PATH, csr, minimal(kumar)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the database refuses a bad row whoever writes it")
    void the_schema_refuses_bad_rows() {
        assertThat(catchThrowable(() -> jdbc.update(
                "INSERT INTO load_requests (consignor_id, company_name, from_place, to_place, "
                        + "status) VALUES (?, 'K', 'A', 'B', 'DONE')", kumar)))
                .hasMessageContaining("ck_lreq_status");
        assertThat(catchThrowable(() -> jdbc.update(
                "INSERT INTO load_requests (consignor_id, company_name, from_place, to_place, "
                        + "trucks_required) VALUES (?, 'K', 'A', 'B', 0)", kumar)))
                .hasMessageContaining("ck_lreq_trucks");
    }
}
