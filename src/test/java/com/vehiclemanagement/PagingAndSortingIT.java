package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Paging and sorting, including the two things TTS got wrong and this does not. */
class PagingAndSortingIT extends ApiTest {

    private String token;

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        for (int i = 1; i <= 12; i++) {
            post("/api/companies", token, body("name", String.format("Company %02d", i)));
        }
    }

    @Test
    @DisplayName("page is 1-based and the envelope is ours, not Spring Data's")
    void the_page_envelope() {
        JsonNode page = get("/api/companies?page=1&page_size=5", token).getBody();

        assertThat(page.get("items")).hasSize(5);
        assertThat(page.get("total").asLong()).isEqualTo(12);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("page_size").asInt()).isEqualTo(5);
        // Spring Data's own shape would leak the persistence library into the contract.
        assertThat(page.has("pageable")).isFalse();
        assertThat(page.has("numberOfElements")).isFalse();
    }

    @Test
    @DisplayName("the snake_case query parameter is the one that is read")
    void page_size_is_spelt_the_way_the_client_spells_it() {
        // Jackson's naming strategy does not touch @RequestParam names, so this is only true
        // because they are spelt out explicitly. A camelCase parameter would silently fall back
        // to the default page size and nobody would notice until a page came back too long.
        assertThat(get("/api/companies?page_size=3", token).getBody().get("items")).hasSize(3);
        assertThat(get("/api/companies?pageSize=3", token).getBody().get("items")).hasSize(12);
    }

    @Test
    void every_row_appears_exactly_once_across_the_pages() {
        Set<Long> seen = new HashSet<>();
        List<Long> all = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            for (JsonNode row : get("/api/companies?page=" + p + "&page_size=5", token)
                    .getBody().get("items")) {
                all.add(row.get("id").asLong());
            }
        }
        seen.addAll(all);

        // This is what the `id` tiebreaker in Sorts buys: without a total order, a row can show
        // up on two pages or on neither.
        assertThat(all).hasSize(12);
        assertThat(seen).hasSize(12);
    }

    @Test
    void an_oversized_page_is_clamped_rather_than_refused() {
        JsonNode page = get("/api/companies?page_size=5000", token).getBody();

        assertThat(page.get("page_size").asInt()).isEqualTo(200);
        assertThat(page.get("items")).hasSize(12);
    }

    @Test
    void page_zero_and_negative_sizes_are_refused() {
        assertThat(get("/api/companies?page=0", token).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(get("/api/companies?page_size=0", token).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("an unknown sort key is rejected, not silently ignored")
    void a_misspelt_sort_is_a_422_listing_the_real_ones() {
        ResponseEntity<JsonNode> refused = get("/api/companies?sort=naem", token);

        // The alternative -- fall back to the default order -- returns a plausible page that is
        // sorted by something else and says nothing, which surfaces later as "sorting doesn't
        // work sometimes".
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        String detail = detail(refused);
        assertThat(detail).contains("naem");
        assertThat(detail).contains("name");
        assertThat(detail).contains("created_at");
    }

    @Test
    void sorting_ascending_and_descending() {
        JsonNode ascending = get("/api/companies?sort=name&page_size=3", token).getBody();
        JsonNode descending = get("/api/companies?sort=-name&page_size=3", token).getBody();

        assertThat(ascending.get("items").get(0).get("name").asText()).isEqualTo("Company 01");
        assertThat(descending.get("items").get(0).get("name").asText()).isEqualTo("Company 12");
    }

    @Test
    void the_search_term_filters_and_a_blank_one_does_not() {
        // No spaces in these terms: TestRestTemplate treats the path as a URI template, so a
        // %20 would be re-encoded to %2520 and the server would search for that literal text.
        // That is a property of the test client, not of the endpoint.
        assertThat(get("/api/companies?q=Company", token).getBody().get("total").asLong())
                .isEqualTo(12);
        assertThat(get("/api/companies?q=01", token).getBody().get("total").asLong())
                .isEqualTo(1);
        // Case-insensitive: the query uses ILIKE, so a UI search box does not have to match case.
        assertThat(get("/api/companies?q=company", token).getBody().get("total").asLong())
                .isEqualTo(12);
        assertThat(get("/api/companies?q=", token).getBody().get("total").asLong())
                .isEqualTo(12);
        assertThat(get("/api/companies?q=nothing-matches-this", token).getBody()
                .get("total").asLong()).isZero();
    }
}
