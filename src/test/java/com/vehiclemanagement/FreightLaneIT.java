package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lane intelligence: "Meerut to Lucknow, mangoes go."
 *
 * <p>What a CSR reliably knows on a call is the route, the commodity, whose cargo it is and
 * whether it runs all year. An earlier version also asked for trips per month and an
 * indicative rate; both were dropped because a field nobody can answer gets answered with a
 * guess, and a guess outranks an honest blank in everything downstream.
 */
class FreightLaneIT extends ApiTest {

    private String token;

    @BeforeEach
    void reset() {
        resetDomainData();
        // Lanes reference cities and goods types, both reference data that resetDomainData
        // keeps. The lanes themselves are domain data and must go, or the report accumulates
        // across tests and every count assertion drifts.
        jdbc.update("DELETE FROM freight_lanes");
        jdbc.update("DELETE FROM goods_types WHERE name_key IN ('mangoes', 'sugarcane')");
        token = staff();
    }

    private Map<String, Object> lane(String from, String to, String goods) {
        return body("from_place", from, "to_place", to, "goods", goods);
    }

    private Map<String, Object> withCompany(String company) {
        Map<String, Object> lane = lane("Meerut", "Lucknow", "Mangoes");
        lane.put("company_name", company);
        return lane;
    }

    private long record(Map<String, Object> request) {
        ResponseEntity<JsonNode> response = post("/api/lanes", token, request);
        assertThat(response.getStatusCode())
                .as("recording failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    @Nested
    @DisplayName("recording what a CSR hears")
    class Recording {

        @Test
        @DisplayName("three fields are enough — from, to, and what")
        void the_minimum_is_three_fields() {
            JsonNode saved = post("/api/lanes", token, lane("Meerut", "Lucknow", "Mangoes"))
                    .getBody();

            assertThat(saved.get("from_place").asText()).isEqualTo("Meerut");
            assertThat(saved.get("to_place").asText()).isEqualTo("Lucknow");
            assertThat(saved.get("goods").asText()).isEqualTo("Mangoes");
            assertThat(saved.get("active").asBoolean()).isTrue();
            // Attributed without being asked: a lane worth acting on is one somebody can be
            // asked about six months later.
            assertThat(saved.get("recorded_by").asText()).isEqualTo("desk");
        }

        @Test
        @DisplayName("everything else is optional and is kept when given")
        void the_rest_is_optional_but_stored() {
            Map<String, Object> full = lane("Meerut", "Lucknow", "Mangoes");
            full.put("company_name", "Sharma Traders");
            full.put("seasonal", true);
            full.put("season", "Apr-Jul");
            full.put("source", "Balwinder Singh");
            full.put("source_mobile", "9812345678");
            full.put("notes", "Packed in crates, needs a covered body.");

            JsonNode saved = post("/api/lanes", token, full).getBody();

            assertThat(saved.get("company_name").asText()).isEqualTo("Sharma Traders");
            assertThat(saved.get("seasonal").asBoolean()).isTrue();
            assertThat(saved.get("season").asText()).isEqualTo("Apr-Jul");
            assertThat(saved.get("source").asText()).isEqualTo("Balwinder Singh");
            assertThat(saved.get("source_mobile").asText()).isEqualTo("9812345678");
        }

        @Test
        @DisplayName("a lane runs all year unless somebody says otherwise")
        void not_seasonal_unless_said() {
            JsonNode saved = post("/api/lanes", token, lane("Jaipur", "Surat", "Cement"))
                    .getBody();

            assertThat(saved.get("seasonal").asBoolean()).isFalse();
            assertThat(saved.get("season").isNull()).isTrue();
        }

        @Test
        @DisplayName("months are dropped when the lane is not seasonal")
        void months_only_mean_something_when_seasonal() {
            // "Runs all year, Apr-Jul" is a contradiction somebody would act on. The flag
            // governs, and the text is the detail underneath it.
            Map<String, Object> contradictory = lane("Meerut", "Lucknow", "Mangoes");
            contradictory.put("seasonal", false);
            contradictory.put("season", "Apr-Jul");

            JsonNode saved = post("/api/lanes", token, contradictory).getBody();

            assertThat(saved.get("seasonal").asBoolean()).isFalse();
            assertThat(saved.get("season").isNull()).isTrue();
        }

        @Test
        @DisplayName("naming a company does not create a customer")
        void a_company_on_a_lane_is_not_a_customer() {
            // Hearsay must not create a row a lorry receipt could then be issued against.
            record(withCompany("Sharma Traders"));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM consignors WHERE name_key = 'sharma traders'",
                    Long.class)).isZero();
        }

        @Test
        @DisplayName("a known place links to its city; an unknown one is kept as text")
        void places_link_when_they_can() {
            JsonNode known = post("/api/lanes", token, lane("Jaipur", "Ludhiana", "Cement"))
                    .getBody();
            assertThat(known.get("from_city_id").isNull()).isFalse();

            // Loads leave factory gates that no city list contains. Refusing the lane over
            // that would lose the intelligence to protect a foreign key nobody needs here.
            JsonNode unknown = post("/api/lanes", token,
                    lane("Kashipur industrial area", "Lucknow", "Plywood")).getBody();
            assertThat(unknown.get("from_city_id").isNull()).isTrue();
            assertThat(unknown.get("from_place").asText()).isEqualTo("Kashipur industrial area");
        }

        @Test
        @DisplayName("a commodity nobody has carried is added to the goods master")
        void a_new_commodity_joins_the_master() {
            record(lane("Meerut", "Lucknow", "Mangoes"));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM goods_types WHERE name_key = 'mangoes'",
                    Long.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("from and to are required; nothing else is")
        void the_route_is_required() {
            assertThat(post("/api/lanes", token,
                    body("to_place", "Lucknow", "goods", "Mangoes")).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post("/api/lanes", token,
                    body("from_place", "Meerut", "goods", "Mangoes")).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

    }

    @Nested
    @DisplayName("the register")
    class Register {

        @Test
        @DisplayName("searches the route, the commodity, the season and the source")
        void search_covers_what_was_written() {
            Map<String, Object> mangoes = lane("Meerut", "Lucknow", "Mangoes");
            mangoes.put("seasonal", true);
            mangoes.put("season", "Apr-Jul");
            mangoes.put("source", "Balwinder Singh");
            mangoes.put("company_name", "Sharma Traders");
            record(mangoes);
            record(lane("Jaipur", "Surat", "Cement"));

            for (String term : java.util.List.of("Meerut", "Lucknow", "Mango", "Apr",
                    "Balwinder", "Sharma")) {
                assertThat(get("/api/lanes?q=" + term, token).getBody().get("total").asInt())
                        .as("searching %s", term).isEqualTo(1);
            }
            assertThat(get("/api/lanes?q=Nowhere", token).getBody().get("total").asInt()).isZero();
        }

        @Test
        @DisplayName("duplicates are kept, because repetition is the evidence")
        void duplicates_are_not_refused() {
            record(lane("Meerut", "Lucknow", "Mangoes"));
            // No unique key across (from, to, goods). Deduplicating would throw away the only
            // measure of how real a lane is.
            assertThat(post("/api/lanes", token, lane("Meerut", "Lucknow", "Mangoes"))
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(get("/api/lanes", token).getBody().get("total").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("seasonal lanes can be listed on their own")
        void seasonal_is_filterable() {
            // The reason is_seasonal is a flag and not only free text: "Apr-Jul" is not
            // something a WHERE clause can read.
            Map<String, Object> mangoes = lane("Meerut", "Lucknow", "Mangoes");
            mangoes.put("seasonal", true);
            mangoes.put("season", "Apr-Jul");
            record(mangoes);
            record(lane("Jaipur", "Surat", "Cement"));

            assertThat(get("/api/lanes?seasonal=true", token).getBody().get("total").asInt())
                    .isEqualTo(1);
            assertThat(get("/api/lanes?seasonal=false", token).getBody().get("total").asInt())
                    .isEqualTo(1);
            assertThat(get("/api/lanes", token).getBody().get("total").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("a lane can be corrected")
        void it_can_be_edited() {
            long id = record(lane("Meerut", "Lucknow", "Mangoes"));

            Map<String, Object> fixed = lane("Meerut", "Kanpur", "Mangoes");
            // Both, because the flag governs the text -- see months_only_mean_something.
            fixed.put("seasonal", true);
            fixed.put("season", "Apr-Jul");
            ResponseEntity<JsonNode> updated = put("/api/lanes/" + id, token, fixed);

            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(updated.getBody().get("to_place").asText()).isEqualTo("Kanpur");
            assertThat(updated.getBody().get("season").asText()).isEqualTo("Apr-Jul");
        }

        @Test
        @DisplayName("there is no delete")
        void there_is_no_delete() {
            long id = record(lane("Meerut", "Lucknow", "Mangoes"));
            assertThat(delete("/api/lanes/" + id, token).getStatusCode())
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("an unknown sort key is refused rather than silently ignored")
        void a_bad_sort_is_refused() {
            assertThat(get("/api/lanes?sort=whatever", token).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("the whole register is behind a login")
    void it_needs_a_token() {
        for (String path : java.util.List.of("/api/lanes", "/api/lanes?seasonal=true")) {
            assertThat(rest.getForEntity(path, JsonNode.class).getStatusCode())
                    .as("anonymous GET %s", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
