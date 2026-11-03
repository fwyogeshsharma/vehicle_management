package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import com.vehiclemanagement.service.ConsignorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load-provider contract terms: what a provider approved, what comes off it, and what the
 * truck is paid. Only the customer is required, and nothing is computed from anything else.
 * The company is picked from the customer master by id, never typed.
 */
class LoadProviderContractIT extends ApiTest {

    private static final String PATH = "/api/load-provider-contracts";

    @Autowired
    private ConsignorService customers;

    private String token;
    private long shree;
    private long acme;

    @BeforeEach
    void reset() {
        resetDomainData();
        jdbc.update("DELETE FROM load_provider_contracts");
        shree = customers.create("Shree Cement", null, null, null).getId();
        acme = customers.create("Acme Ltd", null, null, null).getId();
        // Like lorry receipts, contracts are for ADMIN and TEJJJ_CSR, so work as an administrator.
        token = admin();
    }

    private Map<String, Object> full() {
        return body("consignor_id", shree, "location", "Jaipur",
                "price_approved", 52000, "loading_charges", 800, "unloading_charges", 700,
                "commission_percentage", 2.5, "munshiana", 300, "dala", 400,
                "final_price_for_truck", 49000, "per_tonne_price", 1960,
                "notes", "Valid till March");
    }

    @Test
    @DisplayName("every figure is stored exactly as entered")
    void stores_what_was_typed() {
        ResponseEntity<JsonNode> response = post(PATH, token, full());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode saved = response.getBody();
        assertThat(saved.get("consignor_id").asLong()).isEqualTo(shree);
        // The name is a snapshot taken from the customer master.
        assertThat(saved.get("company_name").asText()).isEqualTo("Shree Cement");
        assertThat(saved.get("location").asText()).isEqualTo("Jaipur");
        assertThat(saved.get("price_approved").decimalValue()).isEqualByComparingTo("52000");
        assertThat(saved.get("loading_charges").decimalValue()).isEqualByComparingTo("800");
        assertThat(saved.get("unloading_charges").decimalValue()).isEqualByComparingTo("700");
        assertThat(saved.get("commission_percentage").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(saved.get("munshiana").decimalValue()).isEqualByComparingTo("300");
        assertThat(saved.get("dala").decimalValue()).isEqualByComparingTo("400");
        // Deliberately NOT 52000 - 800 - 700 - ...: the number on the signed paper wins.
        assertThat(saved.get("final_price_for_truck").decimalValue()).isEqualByComparingTo("49000");
        assertThat(saved.get("per_tonne_price").decimalValue()).isEqualByComparingTo("1960");
        assertThat(saved.get("recorded_by").asText()).isEqualTo("root");
        assertThat(saved.get("active").asBoolean()).isTrue();
        // Jaipur is in the city list, so the place is linked as well as kept as text.
        assertThat(saved.get("city_id").isNull()).isFalse();
    }

    @Test
    @DisplayName("the customer alone is enough")
    void only_the_customer_is_required() {
        ResponseEntity<JsonNode> response = post(PATH, token, body("consignor_id", acme));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("price_approved").isNull()).isTrue();
        assertThat(response.getBody().get("location").isNull()).isTrue();
    }

    @Test
    @DisplayName("a customer from the master list is required")
    void customer_is_required() {
        assertThat(post(PATH, token, body("location", "Jaipur")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // Typing a name is not a way round it: the company is picked, not typed.
        assertThat(post(PATH, token, body("company_name", "Typed Traders")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(PATH, token, body("consignor_id", 999999)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_provider_contracts",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("the name comes from the master, whatever the request says")
    void the_name_is_taken_from_the_master() {
        JsonNode saved = post(PATH, token,
                body("consignor_id", shree, "company_name", "Somebody Else")).getBody();

        assertThat(saved.get("company_name").asText()).isEqualTo("Shree Cement");
    }

    @Test
    @DisplayName("a retired customer cannot be newly chosen, but an existing contract can be corrected")
    void retired_customers() {
        long id = idOf(post(PATH, token, full()));
        customers.retire(shree);

        assertThat(post(PATH, token, full()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String, Object> corrected = full();
        corrected.put("price_approved", 53000);
        assertThat(put(PATH + "/" + id, token, corrected).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an unknown place is kept as text rather than refused")
    void an_unknown_place_is_kept() {
        JsonNode saved = post(PATH, token,
                body("consignor_id", acme, "location", "Kashipur industrial area"))
                .getBody();

        assertThat(saved.get("location").asText()).isEqualTo("Kashipur industrial area");
        assertThat(saved.get("city_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("negative money and a commission outside 0-100 are refused")
    void impossible_figures_are_refused() {
        Map<String, Object> negative = body("consignor_id", acme, "dala", -1);
        assertThat(post(PATH, token, negative).getStatusCode().is4xxClientError()).isTrue();

        Map<String, Object> over = body("consignor_id", acme, "commission_percentage", 101);
        assertThat(post(PATH, token, over).getStatusCode().is4xxClientError()).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_provider_contracts",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("the same provider can have many contracts; edits replace the figures")
    void history_and_edit() {
        long first = idOf(post(PATH, token, full()));
        post(PATH, token, full());
        assertThat(get(PATH, token).getBody().get("total").asLong()).isEqualTo(2);

        Map<String, Object> edited = full();
        edited.put("price_approved", 55000);
        edited.remove("dala");
        JsonNode updated = put(PATH + "/" + first, token, edited).getBody();

        assertThat(updated.get("price_approved").decimalValue()).isEqualByComparingTo("55000");
        // A PUT is a full replacement: a figure left out is cleared, not kept.
        assertThat(updated.get("dala").isNull()).isTrue();
    }

    @Test
    @DisplayName("retiring keeps the row, hides it from the default list, and can be undone")
    void retire_and_restore() {
        long id = idOf(post(PATH, token, full()));

        assertThat(post(PATH + "/" + id + "/retire", token, null).getBody()
                .get("active").asBoolean()).isFalse();
        assertThat(get(PATH + "?active=true", token).getBody().get("total").asLong()).isZero();
        assertThat(get(PATH, token).getBody().get("total").asLong()).isEqualTo(1);

        assertThat(post(PATH + "/" + id + "/restore", token, null).getBody()
                .get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("search matches the company and the location")
    void search() {
        post(PATH, token, full());
        post(PATH, token, body("consignor_id", acme, "location", "Nagpur"));

        assertThat(get(PATH + "?q=cement", token).getBody().get("total").asLong()).isEqualTo(1);
        assertThat(get(PATH + "?q=nagpur", token).getBody().get("total").asLong()).isEqualTo(1);
        assertThat(get(PATH + "?q=zzz", token).getBody().get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("a plain CSR cannot read or write contract terms")
    void a_plain_csr_is_refused() {
        String csr = staff();

        assertThat(get(PATH, csr).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(PATH, csr, full()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the database refuses a bad row whoever writes it")
    void the_schema_refuses_bad_rows() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "INSERT INTO load_provider_contracts (company_name, commission_percentage) "
                        + "VALUES ('X', 150)"))).hasMessageContaining("ck_lpc_commission");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "INSERT INTO load_provider_contracts (company_name, dala) VALUES ('X', -5)")))
                .hasMessageContaining("ck_lpc_amounts");
    }
}
