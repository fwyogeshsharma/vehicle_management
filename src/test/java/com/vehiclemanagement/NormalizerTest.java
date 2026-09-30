package com.vehiclemanagement;

import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.service.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure logic — no database, so this runs even when there is none configured. */
class NormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"9811008120", "+91 98110 08120", "098110 08120", "91-9811008120",
                            " 98110-08120 "})
    void every_spelling_of_one_mobile_stores_the_same(String typed) {
        assertThat(Normalizer.mobile(typed, "mobile")).isEqualTo("9811008120");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "98110081", "5811008120", ""})
    void a_number_that_is_not_an_indian_mobile_is_refused_on_its_field(String bad) {
        assertThatThrownBy(() -> Normalizer.mobile(bad, "alt_mobile"))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getField())
                        .isEqualTo("alt_mobile"));
    }

    @ParameterizedTest
    @CsvSource({"mh 12 ab 1234,MH12AB1234", "MH12AB1234,MH12AB1234", "mh-12-ab-1234,MH12AB1234",
                "dl1c1234,DL1C1234"})
    void a_registration_is_stored_canonically(String typed, String stored) {
        assertThat(Normalizer.registration(typed)).isEqualTo(stored);
    }

    @Test
    void a_bad_registration_reports_against_the_field_the_form_actually_renders() {
        assertThatThrownBy(() -> Normalizer.registration("lorry", "truck_number"))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getField())
                        .isEqualTo("truck_number"));
    }

    @Test
    @DisplayName("known gap: the BH series is rejected, and that is recorded rather than folklore")
    void bh_series_is_a_known_limitation() {
        // 22BH1234AA is on the road today. The regex predates it. This lives in Java rather
        // than in a CHECK precisely so widening it is a commit, not a migration on a live
        // database -- when that day comes, delete this test and add it to the CsvSource above.
        assertThatThrownBy(() -> Normalizer.registration("22BH1234AA"))
                .isInstanceOf(FieldValidationException.class);
    }

    @ParameterizedTest
    @CsvSource({"20 Ton,20", "22.5 MT,22.5", "50T,50", "9 tonnes,9"})
    void capacity_tons_reads_the_number_out_of_free_text(String capacity, String expected) {
        assertThat(Normalizer.capacityTons(capacity)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no idea", "", "heavy"})
    void an_unparseable_capacity_is_null_and_never_an_exception(String capacity) {
        // The column is free text by decision. "heavy" is a capacity someone typed, not a
        // request to reject their vehicle.
        assertThat(Normalizer.capacityTons(capacity)).isNull();
    }

    @Test
    void clean_collapses_whitespace_and_empties_to_null() {
        assertThat(Normalizer.clean("  Ramesh   Kumar ")).isEqualTo("Ramesh Kumar");
        assertThat(Normalizer.clean("   ")).isNull();
        assertThat(Normalizer.clean(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-bad", "WithCaps!"})
    void a_bad_username_is_refused(String bad) {
        assertThatThrownBy(() -> Normalizer.username(bad))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void a_username_is_lower_cased_and_trimmed() {
        assertThat(Normalizer.username("  Asha.Kumar  ")).isEqualTo("asha.kumar");
    }
}
