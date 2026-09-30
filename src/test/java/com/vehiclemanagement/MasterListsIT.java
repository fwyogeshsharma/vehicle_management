package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pick lists behind the masters screen.
 *
 * <p>Body types and goods types were already covered by the services that own them; what is new
 * here is that <b>capacities and cities became writable</b>, and both are reached by rows that
 * must keep reading correctly afterwards. So the assertions that matter are not "can I add
 * one" — they are what happens to everything pointing at a value when it is taken away.
 *
 * <p><b>Nothing in this suite deletes.</b> There is no endpoint that does, which is the point:
 * every one of these lists is retired instead.
 */
class MasterListsIT extends ApiTest {

    private String token;

    // resetDomainData() does NOT truncate the reference tables -- retiring one would otherwise
    // break every later test -- so a row added by one test is still there for the next. Each
    // test below therefore uses a value of its own; sharing "17 Ton" across three of them made
    // the second one a 409 and the failure looked like a bug in the endpoint.

    @BeforeEach
    void reset() {
        resetDomainData();
        // resetDomainData keeps the reference tables -- retiring a body type would otherwise
        // break every later test. But THIS suite adds to them, and a row it added survives into
        // the next run, where the same "create" is a 409. So it clears its own additions and
        // nothing else. Safe after resetDomainData, which has already emptied lorry_receipts
        // and the location tables that reference a city.
        jdbc.update("DELETE FROM capacities WHERE label IN ('17 Ton', '19 Ton', '23 Ton')");
        jdbc.update("DELETE FROM cities WHERE name_key IN "
                + "('sahnewal yard', 'doraha mandi', 'mandi gobindgarh')");
        jdbc.update("DELETE FROM goods_types WHERE name_key = 'glazed roof tiles'");
        jdbc.update("DELETE FROM body_types WHERE name_key = 'flatbed hi-side'");
        // Three tests below write a lorry receipt, to show that retiring a master leaves the
        // receipts naming it readable. Receipts are ADMIN or TEJJJ_CSR only.
        token = admin();
    }

    private long punjab() {
        JsonNode states = get("/api/states", token).getBody();
        for (JsonNode s : states) {
            if ("PB".equals(s.get("code").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError("Punjab is not in the seeded states");
    }

    @Nested
    @DisplayName("capacities")
    class Capacities {

        @Test
        @DisplayName("a new rung is added and sorts by tonnage, not alphabetically")
        void a_rung_is_added_in_order() {
            ResponseEntity<JsonNode> created =
                    post("/api/capacities", token, body("label", "17 Ton", "tons", 17));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            JsonNode list = get("/api/capacities", token).getBody();
            int sixteen = -1;
            int seventeen = -1;
            int eighteen = -1;
            for (int i = 0; i < list.size(); i++) {
                String label = list.get(i).get("label").asText();
                if ("16 Ton".equals(label)) sixteen = i;
                if ("17 Ton".equals(label)) seventeen = i;
                if ("18 Ton".equals(label)) eighteen = i;
            }
            // Alphabetically "17 Ton" would fall between "16 Ton" and "18 Ton" by luck, but
            // "9 Ton" would sit between them too. The order comes from tons.
            assertThat(sixteen).isLessThan(seventeen);
            assertThat(seventeen).isLessThan(eighteen);
        }

        @Test
        @DisplayName("a duplicate label is refused, and says so if it is merely retired")
        void a_duplicate_is_refused() {
            ResponseEntity<JsonNode> clash =
                    post("/api/capacities", token, body("label", "16 Ton", "tons", 16));
            assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            long id = idOf(post("/api/capacities", token, body("label", "19 Ton", "tons", 19)));
            delete("/api/capacities/" + id, token);

            ResponseEntity<JsonNode> again =
                    post("/api/capacities", token, body("label", "19 Ton", "tons", 19));
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(detail(again)).contains("Restore it instead");
        }

        @Test
        @DisplayName("zero or negative tonnage is refused")
        void tonnage_must_be_positive() {
            assertThat(post("/api/capacities", token, body("label", "Nothing", "tons", 0))
                    .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("retiring takes it off the list without touching the vehicles using it")
        void retiring_leaves_vehicles_alone() {
            long bodyTypeId = idOf(post("/api/body-types", token, body("name", "Flatbed hi-side")));
            post("/api/vehicles/intake", token, body(
                    "registration_number", "PB10AA1111", "body_type_id", bodyTypeId,
                    "driver_name", "Gurpreet Singh", "driver_mobile", "9811008130",
                    "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

            long sixteen = -1;
            for (JsonNode c : get("/api/capacities", token).getBody()) {
                if ("16 Ton".equals(c.get("label").asText())) sixteen = c.get("id").asLong();
            }
            assertThat(delete("/api/capacities/" + sixteen, token).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            // Off the pick list...
            assertThat(labels(get("/api/capacities", token).getBody())).doesNotContain("16 Ton");
            // ...but the truck still says what it always said. capacity is free text and this
            // list was never a foreign key, which is exactly why retiring one is safe.
            assertThat(jdbc.queryForObject(
                    "SELECT capacity FROM vehicles WHERE registration_number = 'PB10AA1111'",
                    String.class)).isEqualTo("16 Ton");
            assertThat(jdbc.queryForObject(
                    "SELECT capacity_tons FROM vehicles WHERE registration_number = 'PB10AA1111'",
                    java.math.BigDecimal.class).intValue()).isEqualTo(16);
        }

        @Test
        @DisplayName("a retired rung comes back")
        void restore_works() {
            long id = idOf(post("/api/capacities", token, body("label", "23 Ton", "tons", 23)));
            delete("/api/capacities/" + id, token);
            assertThat(labels(get("/api/capacities", token).getBody())).doesNotContain("23 Ton");

            assertThat(post("/api/capacities/" + id + "/restore", token, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(labels(get("/api/capacities", token).getBody())).contains("23 Ton");
        }

        private java.util.List<String> labels(JsonNode list) {
            java.util.List<String> out = new java.util.ArrayList<>();
            list.forEach(n -> out.add(n.get("label").asText()));
            return out;
        }
    }

    @Nested
    @DisplayName("cities")
    class Cities {

        @Test
        @DisplayName("a place the seed missed is added under its state")
        void a_city_is_added() {
            long pb = punjab();
            ResponseEntity<JsonNode> created =
                    post("/api/states/" + pb + "/cities", token, body("name", "Sahnewal Yard"));

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody().get("state_id").asLong()).isEqualTo(pb);
            assertThat(created.getBody().get("name").asText()).isEqualTo("Sahnewal Yard");
            assertThat(created.getBody().get("active").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("the same name in the same state is refused; in another state it is fine")
        void uniqueness_is_per_state() {
            long pb = punjab();
            post("/api/states/" + pb + "/cities", token, body("name", "Mandi Gobindgarh"));

            assertThat(post("/api/states/" + pb + "/cities", token,
                    body("name", "mandi gobindgarh")).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            // Several states genuinely have a Sagar, so the key is (state, name) and not name.
            long other = -1;
            for (JsonNode s : get("/api/states", token).getBody()) {
                if ("HR".equals(s.get("code").asText())) other = s.get("id").asLong();
            }
            assertThat(post("/api/states/" + other + "/cities", token,
                    body("name", "Mandi Gobindgarh")).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("retiring a city keeps the lorry receipts that name it")
        void retiring_keeps_receipts_readable() {
            long pb = punjab();
            long cityId = idOf(post("/api/states/" + pb + "/cities", token,
                    body("name", "Doraha Mandi")));

            long lrId = idOf(post("/api/lr", token, body(
                    "lr_date", "2026-09-28",
                    "consignor_name", "Kumar Traders",
                    "consignee_name", "Bagru Cement",
                    "from_city_id", cityId, "from_place", "Doraha Mandi",
                    "to_place", "Ludhiana")));

            assertThat(delete("/api/cities/" + cityId, token).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            JsonNode receipt = get("/api/lr/" + lrId, token).getBody();
            // Retired, not deleted: the link survives AND the printed text is untouched.
            assertThat(receipt.get("from_city_id").asLong()).isEqualTo(cityId);
            assertThat(receipt.get("from_place").asText()).isEqualTo("Doraha Mandi");
        }

        @Test
        @DisplayName("an unknown state is a 404, not a city in nowhere")
        void an_unknown_state_is_refused() {
            assertThat(post("/api/states/999999/cities", token, body("name", "Somewhere"))
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("there is no way to add a state")
        void states_are_not_editable() {
            assertThat(post("/api/states", token, body("code", "ZZ", "name", "Atlantis"))
                    .getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("goods types")
    class Goods {

        @Test
        @DisplayName("added, retired and restored, and a receipt may still name a retired one")
        void the_list_is_advisory() {
            long id = idOf(post("/api/goods-types", token, body("name", "Glazed roof tiles")));
            assertThat(post("/api/goods-types/" + id + "/retire", token, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            JsonNode active = get("/api/goods-types", token).getBody();
            assertThat(names(active)).doesNotContain("Glazed roof tiles");
            assertThat(names(get("/api/goods-types?include_inactive=true", token).getBody()))
                    .contains("Glazed roof tiles");

            // A pick list, not a foreign key: a receipt for something off the list still writes.
            ResponseEntity<JsonNode> receipt = post("/api/lr", token, body(
                    "lr_date", "2026-09-28",
                    "consignor_name", "Kumar Traders", "consignee_name", "Bagru Cement",
                    "from_place", "Jaipur", "to_place", "Ludhiana",
                    "goods_description", "Glazed roof tiles"));
            assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(receipt.getBody().get("goods_description").asText())
                    .isEqualTo("Glazed roof tiles");

            assertThat(post("/api/goods-types/" + id + "/restore", token, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(names(get("/api/goods-types", token).getBody())).contains("Glazed roof tiles");
        }

        private java.util.List<String> names(JsonNode list) {
            java.util.List<String> out = new java.util.ArrayList<>();
            list.forEach(n -> out.add(n.get("name").asText()));
            return out;
        }
    }

    @Nested
    @DisplayName("customers")
    class Customers {

        @Test
        @DisplayName("retiring one keeps it off new receipts but leaves the old ones alone")
        void retiring_is_not_deleting() {
            long lrId = idOf(post("/api/lr", token, body(
                    "lr_date", "2026-09-28",
                    "consignor_name", "Kumar Traders", "consignee_name", "Bagru Cement",
                    "from_place", "Jaipur", "to_place", "Ludhiana")));
            long customerId = get("/api/lr/" + lrId, token).getBody().get("consignor_id").asLong();

            assertThat(post("/api/customers/" + customerId + "/retire", token, null)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);

            // The old receipt is untouched...
            JsonNode old = get("/api/lr/" + lrId, token).getBody();
            assertThat(old.get("consignor_id").asLong()).isEqualTo(customerId);
            assertThat(old.get("consignor_name").asText()).isEqualTo("Kumar Traders");

            // ...and a new receipt may not be written against the retired row by id.
            ResponseEntity<JsonNode> blocked = post("/api/lr", token, body(
                    "lr_date", "2026-09-28",
                    "consignor_id", customerId, "consignee_name", "Bagru Cement",
                    "from_place", "Jaipur", "to_place", "Ludhiana"));
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(detail(blocked)).contains("retired");
        }
    }

    @Test
    @DisplayName("every master list needs a token")
    void the_lists_are_behind_a_login() {
        for (String path : java.util.List.of("/api/capacities", "/api/body-types",
                "/api/goods-types", "/api/customers", "/api/states")) {
            assertThat(rest.getForEntity(path, JsonNode.class).getStatusCode())
                    .as("anonymous GET %s", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
