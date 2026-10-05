package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract the mobile field app already speaks.
 *
 * <p>Every assertion here is a <b>copy of FreightDesk's behaviour</b>, not a judgement about
 * what this system would do if it were free to choose. The app is not being rebuilt — it gets a
 * new base URL — so the names, the status words and the status codes are fixed by software we
 * do not control. A test that fails here means an installed copy of the app breaks in the
 * field, which is not the same class of problem as an internal endpoint changing shape.
 *
 * <p>The source of truth is {@code C:/repos/FreightDesk/API_CONTRACT.md} and the handler at
 * {@code webapp/app.py:397}. Where the two disagreed, the handler won — it is what the app was
 * actually tested against.
 *
 * <p><b>These routes are authenticated.</b> They were not originally — anonymous uploads were
 * accepted so an app carrying a FreightDesk token kept working. That stopped being defensible
 * once the field staff became users of this system: with anonymous accepted, deactivating
 * somebody did not stop them uploading, because they only had to drop the header. The wire
 * shape is unchanged; only the credential requirement is new.
 */
class TruckCompatIT extends ApiTest {

    /** A tiny but genuinely decodable JPEG — the store only moves bytes. */
    private static final byte[] PHOTO = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    private String token;

    @BeforeEach
    void reset() {
        resetDomainData();
        // A field executive signing in from the mobile app. CSR, because uploading photos is
        // desk work and not an administrative act.
        token = signedInAs(com.vehiclemanagement.domain.UserType.STAFF,
                "Field Five", "9800000055", "field5");
    }

    // ── the request the app actually sends ──────────────────────────────────────

    /** Every documented form field, at once, with no token. */
    private ResponseEntity<JsonNode> report(int photos, String... formFields) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < photos; i++) {
            form.add("images", new ByteArrayResource(PHOTO) {
                @Override
                public String getFilename() {
                    return "truck.jpg";
                }
            });
        }
        for (int i = 0; i < formFields.length; i += 2) {
            if (formFields[i + 1] != null) {
                form.add(formFields[i], formFields[i + 1]);
            }
        }
        return send(form, token);
    }

    private ResponseEntity<JsonNode> send(MultiValueMap<String, Object> form, String as) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (as != null) {
            headers.setBearerAuth(as);
        }
        return rest.exchange("/api/trucks/report", HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
    }

    private MultiValueMap<String, Object> onePhoto() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("images", new ByteArrayResource(PHOTO) {
            @Override
            public String getFilename() {
                return "truck.jpg";
            }
        });
        return form;
    }

    /** A binary GET with the bearer attached — these routes are no longer open. */
    private ResponseEntity<byte[]> bytes(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private void ocrFinished(long id, String plate, String mobiles, String company) {
        jdbc.update("""
                UPDATE vehicle_intake
                   SET processing_status = 'DONE', processed_at = now(), claimed_at = NULL,
                       ocr_plate = ?, ocr_mobiles = ?::jsonb, ocr_company = ?,
                       ocr_confidence = 'LOW'
                 WHERE id = ?
                """, plate, mobiles == null ? "[]" : mobiles, company, id);
    }

    @Nested
    @DisplayName("POST /api/trucks/report")
    class Report {

        @Test
        @DisplayName("a signed-in field executive can upload")
        void an_authenticated_upload_is_accepted() {
            ResponseEntity<JsonNode> response = report(1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_intake", Long.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an anonymous submission is refused")
        void anonymous_is_refused() {
            // This is the test that makes removing somebody mean something. While anonymous
            // uploads were accepted, deactivating an account did not stop them uploading --
            // they only had to drop the Authorization header.
            assertThat(send(onePhoto(), null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_intake", Long.class))
                    .isZero();
        }

        @Test
        @DisplayName("a deactivated account stops uploading on its very next request")
        void deactivating_somebody_stops_them() {
            assertThat(report(1).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            long id = jdbc.queryForObject(
                    "SELECT id FROM users WHERE username = 'field5'", Long.class);
            users.deactivate(id);

            // Same token, unchanged and unexpired. The converter re-reads the row on every
            // request, so this takes effect now rather than in eight hours.
            assertThat(send(onePhoto(), token).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_intake", Long.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("removing the login stops them too")
        void removing_the_login_stops_them() {
            long id = jdbc.queryForObject(
                    "SELECT id FROM users WHERE username = 'field5'", Long.class);
            users.removeLogin(id);

            assertThat(send(onePhoto(), token).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("the report is attributed to the account, not to what the body claims")
        void the_sender_is_recorded() {
            long id = report(1, "reported_by", "somebody else entirely")
                    .getBody().get("id").asLong();

            // The point of signing in is that the name on the row is one somebody can be asked
            // about six months later, not one they typed.
            assertThat(jdbc.queryForMap("SELECT * FROM vehicle_intake WHERE id = ?", id))
                    .containsEntry("reported_by", "Field Five")
                    .containsEntry("reporter_mobile", "9800000055");
        }

        @Test
        @DisplayName("returns 202 with exactly the six documented keys")
        void response_shape_is_freightdesks() {
            JsonNode body = report(3).getBody();

            assertThat(body.get("id").asLong()).isPositive();
            assertThat(body.get("processing_status").asText()).isEqualTo("QUEUED");
            assertThat(body.get("review_status").asText()).isEqualTo("PENDING");
            assertThat(body.get("images_accepted").asInt()).isEqualTo(3);
            assertThat(body.get("status_url").asText())
                    .isEqualTo("/api/trucks/" + body.get("id").asLong());
            assertThat(body.get("message").asText()).isNotBlank();
        }

        @Test
        @DisplayName("status_url is a path the app can actually poll")
        void status_url_resolves() {
            JsonNode accepted = report(1).getBody();

            ResponseEntity<JsonNode> polled = get(accepted.get("status_url").asText(), token);

            assertThat(polled.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(polled.getBody().get("id").asLong())
                    .isEqualTo(accepted.get("id").asLong());
        }

        @Test
        @DisplayName("every documented form field is stored, not silently dropped")
        void all_fields_are_stored() {
            long id = report(1,
                    "phone_number", "9811008120",
                    "vehicle_number", "RJ14CA1234",
                    "loaded_status", "loaded",
                    "material_type", "Steel",
                    "driver_name", "Ramesh Kumar",
                    "number_of_wheels", "12",
                    "axle_type", "3 Axle",
                    "location", "NH-48, Jaipur",
                    "latitude", "26.9124",
                    "longitude", "75.7873",
                    "captured_at", "2026-06-16T14:30:00+05:30",
                    "reported_by", "driver_app_9087").getBody().get("id").asLong();

            // Read back through SQL rather than the API: the point is that the values reached
            // the table, not that one particular projection happens to expose them.
            assertThat(jdbc.queryForMap("SELECT * FROM vehicle_intake WHERE id = ?", id))
                    .containsEntry("reported_mobile", "9811008120")
                    .containsEntry("reported_plate", "RJ14CA1234")
                    .containsEntry("reported_loaded_status", "loaded")
                    .containsEntry("reported_material_type", "Steel")
                    .containsEntry("reported_driver_name", "Ramesh Kumar")
                    .containsEntry("reported_no_of_wheels", 12)
                    .containsEntry("reported_axle_type", "3 Axle")
                    .containsEntry("location", "NH-48, Jaipur")
                    .containsEntry("latitude", 26.9124)
                    .containsEntry("longitude", 75.7873)
                    // NOT "driver_app_9087" from the body: the report is attributed to the
                    // account that sent it. FreightDesk made the same call -- a free-text
                    // reporter label is the anonymous fallback, and there is no anonymous now.
                    .containsEntry("reported_by", "Field Five");
        }

        @Test
        @DisplayName("a photo is never lost to a mistyped phone number")
        void a_bad_phone_does_not_reject_the_report() {
            // FreightDesk stored whatever digits it got. Our Normalizer THROWS on anything that
            // is not a canonical Indian mobile, which would have turned a misread digit into a
            // 400 and thrown the photographs away — for the one field OCR is about to read off
            // the side of the truck anyway.
            ResponseEntity<JsonNode> response = report(1, "phone_number", "12345");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT reported_mobile FROM vehicle_intake WHERE id = ?", String.class,
                    response.getBody().get("id").asLong()))
                    .isEqualTo("12345");
        }

        @Test
        @DisplayName("a valid phone is still canonicalised to ten digits")
        void a_good_phone_is_still_normalised() {
            // Leniency is only the failure path. +91 and punctuation still collapse, so
            // duplicate checks keep working.
            long id = report(1, "phone_number", "+91 98110-08120").getBody().get("id").asLong();

            assertThat(jdbc.queryForObject(
                    "SELECT reported_mobile FROM vehicle_intake WHERE id = ?", String.class, id))
                    .isEqualTo("9811008120");
        }

        @Test
        @DisplayName("an absurd wheel count is dropped, not turned into a 500")
        void out_of_range_wheels_are_dropped() {
            // ck_intake_reported_wheels would reject 300 at INSERT time. A client bug must not
            // cost the photographs.
            ResponseEntity<JsonNode> response = report(1, "number_of_wheels", "300");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT reported_no_of_wheels FROM vehicle_intake WHERE id = ?", Short.class,
                    response.getBody().get("id").asLong()))
                    .isNull();
        }

        @Test
        @DisplayName("a malformed captured_at falls back to now instead of failing")
        void a_bad_timestamp_does_not_reject_the_report() {
            ResponseEntity<JsonNode> response = report(1, "captured_at", "yesterday-ish");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT captured_at IS NOT NULL FROM vehicle_intake WHERE id = ?",
                    Boolean.class, response.getBody().get("id").asLong())).isTrue();
        }

        @Test
        @DisplayName("more than five photos is refused, as it always was")
        void too_many_photos_is_refused() {
            assertThat(report(6).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /api/trucks/{id}")
    class Poll {

        @Test
        @DisplayName("reads back in FreightDesk's vocabulary, not ours")
        void field_names_are_freightdesks() {
            long id = report(1, "vehicle_number", "RJ14CA1234", "number_of_wheels", "12")
                    .getBody().get("id").asLong();
            ocrFinished(id, "RJ14CA1234", "[\"9811008120\"]", "SHREE BALAJI TRANSPORT");

            JsonNode truck = get("/api/trucks/" + id, token).getBody();

            // The names the app reads. Ours are registration_number / mobiles / company.
            assertThat(truck.get("license_plate").asText()).isEqualTo("RJ14CA1234");
            assertThat(truck.get("company_name").asText()).isEqualTo("SHREE BALAJI TRANSPORT");
            assertThat(truck.get("phone_number").asText()).isEqualTo("9811008120");
            assertThat(truck.get("vehicle_type").asText()).isEqualTo("TRUCK");
            assertThat(truck.get("source").asText()).isEqualTo("image_api");
            // Out as num_wheels, in as number_of_wheels. FreightDesk's asymmetry, preserved.
            assertThat(truck.get("num_wheels").asInt()).isEqualTo(12);
            assertThat(truck.has("number_of_wheels")).isFalse();
        }

        @Test
        @DisplayName("processing_status walks QUEUED then DONE, which is what the app polls on")
        void processing_status_is_the_poll_signal() {
            long id = report(1).getBody().get("id").asLong();

            assertThat(get("/api/trucks/" + id, token)
                    .getBody().get("processing_status").asText()).isEqualTo("QUEUED");

            ocrFinished(id, "MH12AB1234", "[]", null);

            assertThat(get("/api/trucks/" + id, token)
                    .getBody().get("processing_status").asText()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("VERIFIED only when the photos confirm the typed plate")
        void verification_matches_freightdesks_trust_gate() {
            long confirmed = report(1, "vehicle_number", "RJ14CA1234")
                    .getBody().get("id").asLong();
            ocrFinished(confirmed, "RJ14CA1234", "[]", null);

            long mismatched = report(1, "vehicle_number", "RJ14CA1234")
                    .getBody().get("id").asLong();
            ocrFinished(mismatched, "MH12ZZ9999", "[]", null);

            long nothingTyped = report(1).getBody().get("id").asLong();
            ocrFinished(nothingTyped, "MH12AB1234", "[]", null);

            assertThat(verification(confirmed)).isEqualTo("VERIFIED");
            assertThat(verification(mismatched)).isEqualTo("UNVERIFIED");
            // OCR read a plate but nobody typed one to confirm it against. FreightDesk called
            // this UNVERIFIED/OCR_ONLY — unconfirmed, not wrong.
            assertThat(verification(nothingTyped)).isEqualTo("UNVERIFIED");
        }

        @Test
        @DisplayName("no verdict while OCR is still running")
        void verification_is_null_before_ocr_finishes() {
            long id = report(1, "vehicle_number", "RJ14CA1234").getBody().get("id").asLong();

            assertThat(get("/api/trucks/" + id, token)
                    .getBody().get("verification_status").isNull()).isTrue();
        }

        @Test
        @DisplayName("our review words are translated into the app's")
        void review_status_is_translated() {
            long id = report(1).getBody().get("id").asLong();
            assertThat(reviewStatus(id)).isEqualTo("PENDING");

            // COMPLETED is what a CSR finishing the call looks like here; the app knows it as
            // PASSED, the word it pays a contributor on.
            jdbc.update("UPDATE vehicle_intake SET review_status = 'DISCARDED' WHERE id = ?", id);
            assertThat(reviewStatus(id)).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("a failed read is reported with its reason, not as an error")
        void failure_is_a_field_not_a_status_code() {
            long id = report(1).getBody().get("id").asLong();
            jdbc.update("""
                    UPDATE vehicle_intake
                       SET processing_status = 'FAILED', attempts = 1,
                           processing_error = 'no photo could be read', processed_at = now()
                     WHERE id = ?
                    """, id);

            ResponseEntity<JsonNode> response =
                    get("/api/trucks/" + id, token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("processing_status").asText()).isEqualTo("FAILED");
            assertThat(response.getBody().get("processing_error").asText())
                    .isEqualTo("no photo could be read");
        }

        @Test
        @DisplayName("an unknown id is a 404, not an empty record")
        void unknown_id_is_404() {
            assertThat(get("/api/trucks/999999", token).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        private String verification(long id) {
            JsonNode node = get("/api/trucks/" + id, token)
                    .getBody().get("verification_status");
            return node.isNull() ? null : node.asText();
        }

        private String reviewStatus(long id) {
            return get("/api/trucks/" + id, token)
                    .getBody().get("review_status").asText();
        }
    }

    @Nested
    @DisplayName("GET /trucks/{id}/image/{idx}")
    class Photo {

        @Test
        @DisplayName("serves the bytes back from the un-prefixed path the app has compiled in")
        void photo_comes_back() {
            long id = report(2).getBody().get("id").asLong();

            ResponseEntity<byte[]> response =
                    bytes("/trucks/" + id + "/image/0");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(PHOTO);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
            // No /api prefix. FreightDesk's path, and changing it would strand the app.
            assertThat(bytes("/api/trucks/" + id + "/image/0")
                    .getStatusCode()).isNotEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("an index past the last photo is a 404")
        void out_of_range_index_is_404() {
            long id = report(1).getBody().get("id").asLong();

            assertThat(bytes("/trucks/" + id + "/image/5")
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("image_urls points at every photo that was accepted")
        void image_urls_are_usable() {
            long id = report(3).getBody().get("id").asLong();

            JsonNode urls = get("/api/trucks/" + id, token)
                    .getBody().get("image_urls");

            assertThat(urls).hasSize(3);
            for (JsonNode url : urls) {
                assertThat(bytes(url.asText()).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Nested
    @DisplayName("the rest of the API stays shut")
    class StillProtected {

        @Test
        @DisplayName("nothing in this API answers an anonymous caller")
        void nothing_is_open() {
            // There are no permitAll routes left outside /api/auth/login and actuator health.
            for (String path : List.of("/api/vehicles", "/api/companies", "/api/intake",
                    "/api/intake/counts", "/api/users", "/api/trucks/1", "/trucks/1/image/0")) {
                assertThat(rest.getForEntity(path, JsonNode.class).getStatusCode())
                        .as("anonymous GET %s", path)
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("an anonymous caller cannot complete or discard a report")
        void the_csr_actions_are_not_exposed() {
            long id = report(1).getBody().get("id").asLong();

            assertThat(rest.postForEntity("/api/intake/" + id + "/discard", null, JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(rest.postForEntity("/api/intake/" + id + "/complete", null, JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
