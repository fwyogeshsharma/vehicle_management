package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.ProcessingStatus;
import com.vehiclemanagement.domain.ReviewStatus;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.IntakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Photos from the field, from upload to a finished vehicle.
 *
 * <p><b>The claim is not tested here any more.</b> It moved into the Python worker when that
 * worker started polling the table directly, and it is tested there, against a real PostgreSQL,
 * in {@code vehicleManagementOcr/tests/test_db.py} — including the concurrency case FreightDesk's
 * design cannot pass. What is left in this suite is the two human ends and the guarantees the
 * schema itself must hold whoever is writing.
 *
 * <p>Where a test needs a row that OCR has finished with, it writes the {@code ocr_*} columns
 * with raw SQL through {@link #ocrFinished}. That is not a shortcut around a service method —
 * it is precisely what the worker does, and going through Java would test a path that no longer
 * exists.
 */
class IntakeIT extends ApiTest {

    @Autowired
    private IntakeService intake;
    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;

    /** A tiny but genuinely decodable JPEG — the store and the worker only move bytes. */
    private static final byte[] PHOTO = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    @BeforeEach
    void reset() {
        resetDomainData();
        token = staff();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
    }

    private ResponseEntity<JsonNode> uploadPhotos(int count) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < count; i++) {
            form.add("images", new ByteArrayResource(PHOTO) {
                @Override
                public String getFilename() {
                    return "truck.jpg";
                }
            });
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return rest.exchange("/api/intake/photos?reported_plate=MH12AB1234",
                HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);
    }

    /** Exactly what {@code ocr/db.py:record_success} writes, and nothing else. */
    private void ocrFinished(long id, String plate, String mobiles, String company) {
        jdbc.update("""
                UPDATE vehicle_intake
                   SET processing_status = 'DONE', processed_at = now(), claimed_at = NULL,
                       ocr_plate = ?, ocr_mobiles = ?::jsonb, ocr_company = ?,
                       ocr_confidence = 'LOW'
                 WHERE id = ?
                """, plate, mobiles == null ? "[]" : mobiles, company, id);
    }

    /**
     * Exactly what {@code ocr/db.py:record_failure} writes — on the <b>first and only</b>
     * attempt. The worker does not retry, so one failure is what FAILED looks like.
     */
    private void ocrGaveUp(long id, String error) {
        jdbc.update("""
                UPDATE vehicle_intake
                   SET processing_status = 'FAILED', attempts = attempts + 1,
                       processing_error = ?, processed_at = now(), claimed_at = NULL
                 WHERE id = ?
                """, error, id);
    }

    // ── upload ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an upload creates exactly one QUEUED row and stores the bytes")
    void upload_creates_one_queued_row() {
        ResponseEntity<JsonNode> response = uploadPhotos(2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("processing_status").asText()).isEqualTo("QUEUED");
        assertThat(response.getBody().get("photos_accepted").asInt()).isEqualTo(2);

        long id = response.getBody().get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_intake", Long.class))
                .isEqualTo(1);
        // The bytes are retrievable, which is the half of the upload the database cannot prove.
        assertThat(intake.photo(id, 0)).isNotEmpty();
        assertThat(intake.photo(id, 1)).isNotEmpty();
    }

    @Test
    @DisplayName("a row is never visible to the worker without its photos")
    void the_keys_are_present_the_moment_the_row_exists() {
        // The reason the bytes are written BEFORE the insert. FreightDesk inserts first and
        // attaches keys a moment later, which is harmless when an in-process queue hands the id
        // over at the end — but a worker polling this table could see the row in that gap and
        // fail it for "photos no longer available". There is no such gap here: any row a claim
        // can see already has resolvable keys.
        long id = idOf(uploadPhotos(2));

        Integer keys = jdbc.queryForObject(
                "SELECT jsonb_array_length(image_keys) FROM vehicle_intake WHERE id = ?",
                Integer.class, id);
        assertThat(keys).isEqualTo(2);
        assertThat(intake.photo(id, 0)).isNotEmpty();
    }

    @Test
    @DisplayName("a row with no photos cannot exist, whoever writes it")
    void the_database_refuses_an_intake_with_no_photos() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO vehicle_intake (image_keys) VALUES ('[]'::jsonb)"))
                .hasMessageContaining("ck_intake_has_images");
    }

    @Test
    void an_upload_with_no_photos_is_refused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ResponseEntity<JsonNode> response = rest.exchange("/api/intake/photos", HttpMethod.POST,
                new HttpEntity<>(new LinkedMultiValueMap<String, Object>(), headers),
                JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void more_photos_than_the_limit_are_refused() {
        assertThat(uploadPhotos(6).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void the_captured_timestamp_defaults_rather_than_failing_the_upload() {
        // A field device with a wrong clock must not cost us the photos.
        long id = idOf(uploadPhotos(1));
        assertThat(intake.get(id).getCapturedAt()).isBefore(OffsetDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("everything the field app sends is kept, apart from the photos themselves")
    void the_reported_fields_are_stored() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("images", new ByteArrayResource(PHOTO) {
            @Override
            public String getFilename() {
                return "truck.jpg";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        // A URI, not a String: TestRestTemplate treats a String as a URI *template* and
        // re-encodes it, so "%20" would arrive as a literal percent-two-zero.
        java.net.URI url = org.springframework.web.util.UriComponentsBuilder
                .fromPath("/api/intake/photos")
                .queryParam("reported_plate", "MH12AB1234")
                .queryParam("reported_mobile", "9811008120")
                .queryParam("reported_company", "Kumar Roadways")
                .queryParam("reported_by", "Field One")
                .queryParam("reporter_mobile", "9811008122")
                .queryParam("location", "NH48 Manesar")
                .queryParam("latitude", "28.35")
                .queryParam("longitude", "76.93")
                .build().encode().toUri();

        long id = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(form, headers),
                JsonNode.class).getBody().get("id").asLong();

        var row = intake.get(id);
        assertThat(row.getReportedPlate()).isEqualTo("MH12AB1234");
        assertThat(row.getReportedMobile()).isEqualTo("9811008120");
        assertThat(row.getReportedCompany()).isEqualTo("Kumar Roadways");
        assertThat(row.getReportedBy()).isEqualTo("Field One");
        assertThat(row.getReporterMobile()).isEqualTo("9811008122");
        assertThat(row.getLocation()).isEqualTo("NH48 Manesar");
        assertThat(row.getLatitude()).isEqualTo(28.35);
    }

    // ── the two dimensions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a freshly uploaded row is QUEUED for the machine and PENDING for a human")
    void the_two_status_axes_start_independent() {
        long id = idOf(uploadPhotos(1));
        assertThat(intake.get(id).getProcessingStatus()).isEqualTo(ProcessingStatus.QUEUED);
        assertThat(intake.get(id).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("a row OCR gave up on is still workable by a CSR")
    void a_failed_read_does_not_block_the_human() {
        // The case a single status column could not express, and the reason there are two.
        // On real field photos OCR returns a plate on about one in four; a human looking at the
        // same photo can very often read it. Making them wait for a retry would be absurd.
        long id = idOf(uploadPhotos(1));
        ocrGaveUp(id, "no text found");

        ResponseEntity<JsonNode> completed = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().get("review_status").asText()).isEqualTo("COMPLETED");
        // The machine's verdict is untouched by the human's: it did fail, and the row says so.
        assertThat(completed.getBody().get("processing_status").asText()).isEqualTo("FAILED");
        // And since nothing retries a failure, completing by hand is not a shortcut past a
        // pending second attempt -- it is the only way this report was ever going to finish.
    }

    @Test
    @DisplayName("an unknown value in either status column is refused by the database")
    void the_status_columns_are_constrained() {
        long id = idOf(uploadPhotos(1));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE vehicle_intake SET processing_status = 'NEARLY' WHERE id = ?", id))
                .hasMessageContaining("ck_intake_processing");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE vehicle_intake SET review_status = 'MAYBE' WHERE id = ?", id))
                .hasMessageContaining("ck_intake_review");
    }

    // ── correcting what the photo says ──────────────────────────────────────────

    @Test
    @DisplayName("a correction wins over the read, and the read survives it")
    void correcting_does_not_destroy_what_ocr_read() {
        // The whole reason these are separate columns. OCR returned WC32KN7996 for a truck
        // painted UP32 KN 7996; if the correction overwrote it, the evidence that the engine
        // misreads hand-painted state codes would vanish one helpful fix at a time.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "WC32KN7996", "[\"9878770969\"]", "GURU NANAK");

        ResponseEntity<JsonNode> fixed = patch("/api/intake/" + id, token,
                body("plate", "UP32KN7996"));

        assertThat(fixed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixed.getBody().get("plate").asText()).isEqualTo("UP32KN7996");
        assertThat(fixed.getBody().get("edited_plate").asText()).isEqualTo("UP32KN7996");
        assertThat(fixed.getBody().get("ocr_plate").asText()).isEqualTo("WC32KN7996");
        assertThat(fixed.getBody().get("edited_by").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a field left out of the request is left alone")
    void a_partial_correction_touches_only_what_it_names() {
        // Without the null/empty distinction, fixing the company would silently wipe the plate.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", "[\"9878770969\"]", "KUMER RODWAYS");

        JsonNode fixed = patch("/api/intake/" + id, token,
                body("company", "Kumar Roadways")).getBody();

        assertThat(fixed.get("company").asText()).isEqualTo("Kumar Roadways");
        assertThat(fixed.get("plate").asText()).isEqualTo("MH12AB1234");
        assertThat(fixed.get("edited_plate").isNull()).isTrue();
        assertThat(fixed.get("mobiles").get(0).asText()).isEqualTo("9878770969");
    }

    @Test
    @DisplayName("an emptied box clears the value rather than falling back to the read")
    void an_explicit_blank_is_a_correction_not_an_omission() {
        // "The machine read a plate but there isn't one on this truck" has to be expressible,
        // and it is the one case where a blank must NOT fall back to what OCR said.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "TRANSPORT", null, null);

        JsonNode fixed = patch("/api/intake/" + id, token, body("plate", "")).getBody();

        assertThat(fixed.get("plate").isNull()).isTrue();
        assertThat(fixed.get("edited_plate").asText()).isEmpty();
        assertThat(fixed.get("ocr_plate").asText()).isEqualTo("TRANSPORT");
    }

    @Test
    @DisplayName("numbers are cleaned and de-duplicated, not rejected")
    void correcting_the_numbers() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", "[\"9878770969\"]", null);

        JsonNode fixed = patch("/api/intake/" + id, token,
                body("mobiles", List.of("98787-70969", "84370 36313", "9878770969", "")))
                .getBody();

        assertThat(fixed.get("mobiles")).hasSize(2);
        assertThat(fixed.get("mobiles").get(0).asText()).isEqualTo("9878770969");
        assertThat(fixed.get("mobiles").get(1).asText()).isEqualTo("8437036313");
    }

    @Test
    @DisplayName("a half-typed plate is accepted mid-correction")
    void a_correction_is_not_validated_like_a_registration() {
        // Someone is still typing. The hard check belongs at completion, where a vehicle is
        // actually created -- refusing here would fight the person using it.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, null, null, null);

        assertThat(patch("/api/intake/" + id, token, body("plate", "UP32K")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the completion form starts from the correction, not the read")
    void completing_uses_the_corrected_value() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "WC32KN7996", null, null);
        patch("/api/intake/" + id, token, body("plate", "UP32KN7996"));

        ResponseEntity<JsonNode> completed = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "UP32KN7996", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        long vehicleId = completed.getBody().get("vehicle_id").asLong();
        assertThat(get("/api/vehicles/" + vehicleId, token).getBody()
                .get("registration_number").asText()).isEqualTo("UP32KN7996");
    }

    @Test
    @DisplayName("a reviewed row cannot be edited")
    void correcting_after_the_decision_is_refused() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);
        post("/api/intake/" + id + "/discard", token, body("reason", "not a truck"));

        ResponseEntity<JsonNode> refused = patch("/api/intake/" + id, token,
                body("plate", "MH12AB9999"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("cannot be edited");
    }

    @Test
    @DisplayName("the driver's name is kept on the row before completion")
    void the_driver_name_survives_the_call() {
        // OCR cannot read a name off a truck, so this has no ocr_ counterpart. Before it existed
        // a CSR who learned the name and was told to call back had nowhere to put it.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);

        JsonNode saved = patch("/api/intake/" + id, token,
                body("driver_name", "Suresh Patil")).getBody();

        assertThat(saved.get("driver_name").asText()).isEqualTo("Suresh Patil");
        assertThat(get("/api/intake/" + id, token).getBody()
                .get("summary").get("driver_name").asText()).isEqualTo("Suresh Patil");
    }

    // ── what is already on file ─────────────────────────────────────────────────

    @Test
    @DisplayName("the lookup finds a company by the same match completion uses")
    void the_lookup_reports_an_existing_company() {
        post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB1111", "body_type_id", bodyTypeId,
                "driver_name", "Someone", "driver_mobile", "9811008199",
                "company_name", "Kumar Roadways",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        // Case-insensitive, because that is exactly how findByNameKey matches.
        JsonNode found = get("/api/intake/lookup?company=kumar roadways", token).getBody();
        assertThat(found.get("company").get("name").asText()).isEqualTo("Kumar Roadways");

        // And a near-miss is NOT a match, which is the thing worth warning a CSR about: it
        // would create a second company holding half the same firm's trucks.
        assertThat(get("/api/intake/lookup?company=Kumar Roadway", token)
                .getBody().get("company").isNull()).isTrue();
    }

    @Test
    @DisplayName("the lookup names the person a number already belongs to")
    void the_lookup_reports_an_existing_driver() {
        post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB2222", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9878770969",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        JsonNode found = get(
                "/api/intake/lookup?mobile=9878770969&mobile=8437036313", token).getBody();

        // Only the number that IS someone comes back; the unknown one is simply absent.
        assertThat(found.get("people")).hasSize(1);
        assertThat(found.get("people").get(0).get("mobile").asText()).isEqualTo("9878770969");
        assertThat(found.get("people").get(0).get("name").asText()).isEqualTo("Suresh Patil");
    }

    @Test
    @DisplayName("a number nobody holds, and a name nobody uses, come back empty not missing")
    void the_lookup_is_empty_when_nothing_matches() {
        JsonNode found = get(
                "/api/intake/lookup?company=Nobody Transport&mobile=9000000001", token).getBody();

        assertThat(found.get("company").isNull()).isTrue();
        assertThat(found.get("people")).isEmpty();
    }

    @Test
    @DisplayName("a half-typed number is ignored rather than guessed at")
    void the_lookup_skips_incomplete_numbers() {
        // The CSR is mid-keystroke. Matching on a prefix would report a person they have not
        // finished naming, and the answer would change under them as they type.
        assertThat(get("/api/intake/lookup?mobile=98787", token).getBody().get("people"))
                .isEmpty();
    }

    @Test
    @DisplayName("the preview and the write agree: what it says will be reused, is")
    void the_lookup_matches_what_completion_actually_does() {
        post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB3333", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9878770969",
                "company_name", "Kumar Roadways",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));
        long companyId = get("/api/intake/lookup?company=Kumar Roadways", token)
                .getBody().get("company").get("id").asLong();

        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB4444", null, null);
        long vehicleId = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB4444", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9878770969",
                "company_name", "Kumar Roadways",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22))
                .getBody().get("vehicle_id").asLong();

        // The company the preview named is the company the truck ended up under. If these ever
        // diverge the preview is lying, which is worse than not having one.
        assertThat(get("/api/vehicles/" + vehicleId, token).getBody()
                .get("owner_company_id").asLong()).isEqualTo(companyId);
    }

    // ── retry ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a human can put a failed row back in the queue — nothing else will")
    void a_failed_row_can_be_put_back_in_the_queue() {
        long id = idOf(uploadPhotos(1));
        ocrGaveUp(id, "boom");

        ResponseEntity<JsonNode> retried = post("/api/intake/" + id + "/retry", token, null);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().get("processing_status").asText()).isEqualTo("QUEUED");
        assertThat(retried.getBody().get("processing_error").isNull()).isTrue();
    }

    @Test
    @DisplayName("asking again does not reset the count of how many times we have asked")
    void retrying_keeps_the_attempt_history() {
        // A row on its fourth attempt is worth someone noticing. Resetting to zero each time
        // would hide a photo that has been fought over all morning.
        long id = idOf(uploadPhotos(1));
        ocrGaveUp(id, "boom");
        post("/api/intake/" + id + "/retry", token, null);
        ocrGaveUp(id, "boom again");

        ResponseEntity<JsonNode> retried = post("/api/intake/" + id + "/retry", token, null);

        assertThat(retried.getBody().get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a row a worker is holding is not re-queued under it")
    void retrying_something_in_progress_is_refused() {
        long id = idOf(uploadPhotos(1));
        jdbc.update("UPDATE vehicle_intake SET processing_status = 'PROCESSING', "
                + "claimed_at = now() WHERE id = ?", id);

        ResponseEntity<JsonNode> refused = post("/api/intake/" + id + "/retry", token, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("reading this one now");
    }

    // ── completion ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completing creates the vehicle through the existing intake path")
    void completing_creates_a_vehicle() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", "[\"9811008120\", \"9811008199\"]", "Kumar Roadways");

        ResponseEntity<JsonNode> completed = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "company_name", "Kumar Roadways",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().get("review_status").asText()).isEqualTo("COMPLETED");
        long vehicleId = completed.getBody().get("vehicle_id").asLong();

        // Created by the same path as the manual form, so the driver is hired and assigned.
        JsonNode drivers = get("/api/vehicles/" + vehicleId + "/drivers", token).getBody();
        assertThat(drivers).hasSize(1);
        assertThat(drivers.get(0).get("name").asText()).isEqualTo("Suresh Patil");
    }

    @Test
    @DisplayName("every number OCR read comes back, not just the best one")
    void the_worklist_carries_all_the_numbers() {
        // A truck's side routinely carries several. Offering only one meant a telecaller who
        // could not reach the first had no second number, though it was in the same photo.
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", "[\"9878770969\", \"8437036313\"]", null);

        JsonNode found = get("/api/intake?tab=TO_CALL", token).getBody().get("items").get(0);

        assertThat(found.get("ocr_mobiles")).hasSize(2);
        assertThat(found.get("ocr_mobiles").get(0).asText()).isEqualTo("9878770969");
        assertThat(found.get("ocr_mobiles").get(1).asText()).isEqualTo("8437036313");
    }

    @Test
    @DisplayName("reading no numbers is an empty list, never null")
    void no_numbers_is_an_empty_list() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, null, null, null);

        JsonNode found = get("/api/intake/" + id, token).getBody().get("summary");
        assertThat(found.get("ocr_mobiles").isArray()).isTrue();
        assertThat(found.get("ocr_mobiles")).isEmpty();
    }

    @Test
    @DisplayName("a second number for the driver and one for the company are both stored")
    void completing_records_every_number_the_csr_gives() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", "[\"9878770969\", \"8437036313\"]", "Kumar Roadways");

        ResponseEntity<JsonNode> completed = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil",
                "driver_mobile", "9878770969",
                "driver_alt_mobile", "9811008120",
                "company_name", "Kumar Roadways",
                "company_mobile", "8437036313",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The second number lands on the DRIVER, the third on the COMPANY -- different records,
        // because "call the truck" means two different people depending on what you want.
        assertThat(jdbc.queryForObject(
                "SELECT alt_mobile FROM users WHERE mobile = '9878770969'", String.class))
                .isEqualTo("9811008120");
        assertThat(jdbc.queryForObject(
                "SELECT mobile FROM companies WHERE name = 'Kumar Roadways'", String.class))
                .isEqualTo("8437036313");
    }

    @Test
    @DisplayName("the driver's second number cannot be their first")
    void the_alternate_number_must_differ() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);

        ResponseEntity<JsonNode> refused = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "driver_alt_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(detail(refused)).contains("same as the driver");
    }

    @Test
    @DisplayName("a blank second number does not erase one already on file")
    void an_empty_alternate_leaves_the_existing_one_alone() {
        // "I did not ask" and "delete what you had" are different things, and the form cannot
        // tell them apart -- so the safe reading is the only one.
        long first = idOf(uploadPhotos(1));
        ocrFinished(first, "MH12AB1234", null, null);
        post("/api/intake/" + first + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "driver_alt_mobile", "9811008122",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        long second = idOf(uploadPhotos(1));
        ocrFinished(second, "MH12AB9999", null, null);
        post("/api/intake/" + second + "/complete", token, body(
                "registration_number", "MH12AB9999", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(jdbc.queryForObject(
                "SELECT alt_mobile FROM users WHERE mobile = '9811008121'", String.class))
                .isEqualTo("9811008122");
    }

    @Test
    @DisplayName("completing records who did it")
    void completion_is_attributed() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);
        post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(intake.get(id).getReviewedBy()).isNotBlank();
        assertThat(intake.get(id).getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("a plate already on file is named, not thrown as a constraint violation")
    void completing_a_duplicate_plate_says_which_vehicle() {
        post("/api/vehicles/intake", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Someone Else", "driver_mobile", "9811008199",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);

        ResponseEntity<JsonNode> refused = post("/api/intake/" + id + "/complete", token, body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(refused)).contains("already registered");
        // Still workable: the CSR can discard it, or open the vehicle that already exists.
        assertThat(intake.get(id).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void completing_twice_is_refused() {
        long id = idOf(uploadPhotos(1));
        ocrFinished(id, "MH12AB1234", null, null);
        Map<String, Object> request = body(
                "registration_number", "MH12AB1234", "body_type_id", bodyTypeId,
                "driver_name", "Suresh Patil", "driver_mobile", "9811008121",
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22);
        post("/api/intake/" + id + "/complete", token, request);

        assertThat(post("/api/intake/" + id + "/complete", token, request).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void discarding_is_terminal() {
        long id = idOf(uploadPhotos(1));

        ResponseEntity<JsonNode> discarded = post("/api/intake/" + id + "/discard", token,
                body("reason", "not a truck"));

        assertThat(discarded.getBody().get("review_status").asText()).isEqualTo("DISCARDED");
        assertThat(discarded.getBody().get("review_note").asText()).isEqualTo("not a truck");
        assertThat(post("/api/intake/" + id + "/discard", token, body("reason", "again"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── the schema's own guards ─────────────────────────────────────────────────

    @Test
    @DisplayName("ck_intake_vehicle holds against raw SQL, not just the service")
    void a_completed_row_must_name_its_vehicle() {
        long id = idOf(uploadPhotos(1));

        // The whole reason this is a CHECK and not a Java assertion: the table now has a second
        // writer, in another language, that this codebase cannot see. It must hold against a
        // native UPDATE, a data migration, and psql.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE vehicle_intake SET review_status = 'COMPLETED' WHERE id = ?", id))
                .hasMessageContaining("ck_intake_vehicle");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE vehicle_intake SET vehicle_id = 1 WHERE id = ?", id))
                .hasMessageContaining("ck_intake_vehicle");
    }

    // ── the worklist ────────────────────────────────────────────────────────────

    @Test
    void the_worklist_slices_both_axes_and_counts_every_tab() {
        long toCall = idOf(uploadPhotos(1));
        long failed = idOf(uploadPhotos(1));
        idOf(uploadPhotos(1));                       // still QUEUED
        ocrFinished(toCall, "MH12AB1234", null, null);
        ocrGaveUp(failed, "no text found");

        ResponseEntity<JsonNode> page = get("/api/intake?tab=TO_CALL", token);
        assertThat(page.getStatusCode())
                .withFailMessage("worklist returned %s: %s", page.getStatusCode(), page.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().get("total").asLong()).isEqualTo(1);
        assertThat(page.getBody().get("items").get(0).get("id").asLong()).isEqualTo(toCall);

        JsonNode counts = get("/api/intake/counts", token).getBody();
        assertThat(counts.get("to_call").asLong()).isEqualTo(1);
        assertThat(counts.get("waiting").asLong()).isEqualTo(1);
        assertThat(counts.get("failed").asLong()).isEqualTo(1);
        assertThat(counts.get("all").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("the queue is worked oldest first")
    void the_worklist_is_ordered_by_age() {
        long first = idOf(uploadPhotos(1));
        long second = idOf(uploadPhotos(1));
        ocrFinished(first, "MH12AB1234", null, null);
        ocrFinished(second, "MH12AB5678", null, null);
        jdbc.update("UPDATE vehicle_intake SET created_at = now() - interval '1 day' WHERE id = ?",
                second);

        JsonNode items = get("/api/intake?tab=TO_CALL", token).getBody().get("items");
        assertThat(items.get(0).get("id").asLong()).isEqualTo(second);
    }

    @Test
    void the_photo_endpoint_streams_the_image() {
        long id = idOf(uploadPhotos(1));

        ResponseEntity<byte[]> photo = rest.exchange("/api/intake/" + id + "/photo/0",
                HttpMethod.GET, authEntity(), byte[].class);

        assertThat(photo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(photo.getBody()).isNotEmpty();
        assertThat(rest.exchange("/api/intake/" + id + "/photo/9", HttpMethod.GET, authEntity(),
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpEntity<Void> authEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    // ── what used to be open, and no longer exists ──────────────────────────────

    @Test
    @DisplayName("nothing under /api/intake is reachable without a token any more")
    void the_worker_endpoints_are_gone() {
        // These two used to sit outside the authentication filter, gated only on a shared
        // secret, because the worker had no account. The worker now reads the database
        // directly, so the routes were deleted along with the secret — and the security
        // config's permitAll entries with them. A 404 or a 401 both prove the point; what must
        // NOT happen is a 200.
        assertThat(get("/api/intake/claim", null).getStatusCode().value())
                .isIn(401, 403, 404);
        assertThat(post("/api/intake/1/result", null, Map.of()).getStatusCode().value())
                .isIn(401, 403, 404);

        // And the worklist itself still needs one.
        assertThat(get("/api/intake?tab=TO_CALL", null).getStatusCode().value())
                .isIn(401, 403);
    }
}
