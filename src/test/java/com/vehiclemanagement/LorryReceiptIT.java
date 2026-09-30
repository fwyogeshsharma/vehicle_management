package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.service.BodyTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lorry receipts, end to end over HTTP.
 *
 * <p>tts, which this was ported from, has <b>no tests for its lorry receipts at all</b>. Most
 * of what is asserted here is therefore not "does the port match" but "does the thing the port
 * was supposed to fix actually hold" — the numbering under a failed write, the status
 * transitions, cancellation instead of deletion, and above all the snapshot surviving a change
 * to the masters it was taken from.
 */
class LorryReceiptIT extends ApiTest {

    @Autowired
    private BodyTypeService bodyTypes;

    private String token;
    private long bodyTypeId;

    @BeforeEach
    void reset() {
        resetDomainData();
        // Lorry receipts are ADMIN or TEJJJ_CSR only, so the suite works as an administrator.
        // A plain CSR is refused, and AccessIT below is where that is asserted.
        token = admin();
        bodyTypeId = bodyTypes.ensure("Open body").getId();
    }

    /** The smallest receipt the API will accept: a date and both ends of the consignment. */
    private Map<String, Object> minimal() {
        return body(
                "lr_date", "2026-09-28",
                "consignor_name", "Kumar Traders",
                "consignee_name", "Bagru Cement",
                "from_place", "Jaipur",
                "to_place", "Ludhiana");
    }

    private ResponseEntity<JsonNode> create(Map<String, Object> request) {
        return post("/api/lr", token, request);
    }

    /** A truck in the register, with the driver the intake path creates alongside it. */
    private long registerVehicle(String registration, String driver, String mobile) {
        ResponseEntity<JsonNode> response = post("/api/vehicles/intake", token, body(
                "registration_number", registration,
                "body_type_id", bodyTypeId,
                "driver_name", driver,
                "driver_mobile", mobile,
                // VehicleService requires these four even though the columns are nullable --
                // see "Where a rule belongs" in CLAUDE.md.
                "no_of_axles", 2, "no_of_wheels", 6, "capacity", "16 Ton", "length_ft", 22));
        assertThat(response.getStatusCode())
                .as("registering %s failed: %s", registration, response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private JsonNode created(Map<String, Object> request) {
        ResponseEntity<JsonNode> response = create(request);
        assertThat(response.getStatusCode())
                .as("create failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    // ── numbering ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("numbering")
    class Numbering {

        @Test
        @DisplayName("runs LR-001, LR-002, ... and the preview agrees with what is issued")
        void numbers_run_in_sequence() {
            assertThat(get("/api/lr/next-number", token).getBody().get("lr_number").asText())
                    .isEqualTo("LR-001");

            assertThat(created(minimal()).get("lr_number").asText()).isEqualTo("LR-001");
            assertThat(created(minimal()).get("lr_number").asText()).isEqualTo("LR-002");

            assertThat(get("/api/lr/next-number", token).getBody().get("lr_number").asText())
                    .isEqualTo("LR-003");
        }

        @Test
        @DisplayName("a rejected write leaves no hole in the series")
        void a_failed_create_does_not_burn_a_number() {
            created(minimal());

            // Rejected after the counter was taken: the whole transaction rolls back, and with
            // it the increment. A PostgreSQL sequence would NOT roll back, and LR-002 would be
            // missing from the register for ever -- which is the reason lr_counters exists.
            Map<String, Object> bad = minimal();
            bad.put("advance", 5000);
            bad.put("freight_charges", 100);
            assertThat(create(bad).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            assertThat(created(minimal()).get("lr_number").asText()).isEqualTo("LR-002");
        }

        @Test
        @DisplayName("a caller may supply its own number, and it must be free")
        void an_explicit_number_is_honoured_and_unique() {
            Map<String, Object> own = minimal();
            own.put("lr_number", "bk-7741");
            assertThat(created(own).get("lr_number").asText()).isEqualTo("BK-7741");

            ResponseEntity<JsonNode> again = create(own);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(detail(again)).contains("BK-7741");
        }
    }

    // ── the snapshot, which is the whole point ──────────────────────────────────

    @Nested
    @DisplayName("the printed snapshot")
    class Snapshot {

        @Test
        @DisplayName("renaming a customer does not rewrite receipts already issued to them")
        void a_rename_leaves_history_alone() {
            long id = created(minimal()).get("id").asLong();
            long customerId = get("/api/lr/" + id, token).getBody().get("consignor_id").asLong();

            ResponseEntity<JsonNode> renamed = put("/api/customers/" + customerId, token,
                    body("name", "Kumar Traders & Sons"));
            assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);

            JsonNode lr = get("/api/lr/" + id, token).getBody();
            // The link still points at the customer -- but the document says what it said.
            assertThat(lr.get("consignor_id").asLong()).isEqualTo(customerId);
            assertThat(lr.get("consignor_name").asText()).isEqualTo("Kumar Traders");
        }

        @Test
        @DisplayName("the truck's details are copied, not looked up")
        void vehicle_details_are_copied() {
            long vehicleId = registerVehicle("RJ14CA1234", "Ramesh Kumar", "9811008120");

            Map<String, Object> request = minimal();
            request.put("vehicle_id", vehicleId);
            JsonNode lr = created(request);

            // Left blank in the request, so both were filled from the vehicle.
            assertThat(lr.get("vehicle_number").asText()).isEqualTo("RJ14CA1234");
            assertThat(lr.get("vehicle_type").asText()).isEqualTo("Open body");

            // Re-registering the truck must not alter the receipt.
            jdbc.update("UPDATE vehicles SET registration_number = 'RJ14XX9999' WHERE id = ?",
                    vehicleId);
            assertThat(get("/api/lr/" + lr.get("id").asLong(), token)
                    .getBody().get("vehicle_number").asText()).isEqualTo("RJ14CA1234");
        }

        @Test
        @DisplayName("deleting the linked truck clears the link and keeps the document")
        void a_deleted_truck_leaves_the_text_behind() {
            long vehicleId = registerVehicle("RJ14CA1234", "Ramesh Kumar", "9811008120");
            Map<String, Object> request = minimal();
            request.put("vehicle_id", vehicleId);
            long id = created(request).get("id").asLong();

            jdbc.update("DELETE FROM vehicle_x_user WHERE vehicle_id = ?", vehicleId);
            jdbc.update("DELETE FROM vehicles WHERE id = ?", vehicleId);

            JsonNode lr = get("/api/lr/" + id, token).getBody();
            assertThat(lr.get("vehicle_id").isNull()).isTrue();
            assertThat(lr.get("vehicle_number").asText()).isEqualTo("RJ14CA1234");
        }

        @Test
        @DisplayName("a typed name that is not on file creates the customer and the goods type")
        void typing_a_new_name_creates_the_master() {
            Map<String, Object> request = minimal();
            request.put("goods_description", "Handmade tiles");
            JsonNode lr = created(request);

            assertThat(lr.get("consignor_id").isNull()).isFalse();
            assertThat(lr.get("goods_type_id").isNull()).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM consignors WHERE name_key = 'kumar traders'",
                    Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM goods_types WHERE name_key = 'handmade tiles'",
                    Long.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("the same customer at both ends is one row, not two")
        void one_party_row_serves_both_roles() {
            Map<String, Object> request = minimal();
            request.put("consignee_name", "Kumar Traders");
            JsonNode lr = created(request);

            assertThat(lr.get("consignor_id").asLong()).isEqualTo(lr.get("consignee_id").asLong());
        }
    }

    // ── places ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("places")
    class Places {

        @Test
        @DisplayName("a place matching a city is linked to it")
        void a_known_city_is_linked() {
            JsonNode lr = created(minimal());
            assertThat(lr.get("from_city_id").isNull()).isFalse();
            assertThat(lr.get("from_place").asText()).isEqualTo("Jaipur");
        }

        @Test
        @DisplayName("a place that is not a city is kept as text rather than refused")
        void an_unknown_place_is_still_accepted() {
            // tts REQUIRES a city match and rejects the receipt without one. Loads are picked
            // up at factory gates on highways, and losing the receipt over that is the wrong
            // trade -- the link is a bonus, the document is not.
            Map<String, Object> request = minimal();
            request.put("from_place", "NH-48, near Bagru");
            JsonNode lr = created(request);

            assertThat(lr.get("from_city_id").isNull()).isTrue();
            assertThat(lr.get("from_place").asText()).isEqualTo("NH-48, near Bagru");
        }

        @Test
        @DisplayName("a place is required at both ends")
        void a_missing_place_is_refused() {
            Map<String, Object> request = minimal();
            request.remove("to_place");
            assertThat(create(request).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── money ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("charges")
    class Charges {

        @Test
        @DisplayName("total and balance are computed by the database, not the caller")
        void totals_are_generated() {
            Map<String, Object> request = minimal();
            request.put("freight_charges", 12000);
            request.put("loading_charges", 800);
            request.put("unloading_charges", 700);
            request.put("other_charges", 500);
            request.put("advance", 4000);

            JsonNode lr = created(request);
            assertThat(lr.get("total_charges").decimalValue().intValue()).isEqualTo(14000);
            assertThat(lr.get("balance").decimalValue().intValue()).isEqualTo(10000);
        }

        @Test
        @DisplayName("an advance larger than the total is refused, naming the field")
        void advance_cannot_exceed_the_total() {
            Map<String, Object> request = minimal();
            request.put("freight_charges", 1000);
            request.put("advance", 5000);

            ResponseEntity<JsonNode> response = create(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(detail(response)).contains("advance");
        }

        @Test
        @DisplayName("negative charges are refused")
        void charges_cannot_be_negative() {
            Map<String, Object> request = minimal();
            request.put("freight_charges", -100);
            assertThat(create(request).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("charges default to zero rather than null")
        void charges_default_to_zero() {
            JsonNode lr = created(minimal());
            assertThat(lr.get("total_charges").decimalValue().intValue()).isZero();
            assertThat(lr.get("balance").decimalValue().intValue()).isZero();
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("booked to in transit to delivered")
        void the_ordinary_path() {
            long id = created(minimal()).get("id").asLong();
            assertThat(get("/api/lr/" + id, token).getBody().get("status").asText())
                    .isEqualTo("BOOKED");

            assertThat(post("/api/lr/" + id + "/status", token, body("status", "IN_TRANSIT"))
                    .getBody().get("status").asText()).isEqualTo("IN_TRANSIT");
            assertThat(post("/api/lr/" + id + "/status", token, body("status", "DELIVERED"))
                    .getBody().get("status").asText()).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("a delivered receipt cannot go back")
        void delivered_is_terminal() {
            long id = created(minimal()).get("id").asLong();
            post("/api/lr/" + id + "/status", token, body("status", "DELIVERED"));

            // tts writes whatever status arrives, so this succeeds there. A signed-for
            // consignment quietly returning to "booked" is a correction nobody made on purpose.
            ResponseEntity<JsonNode> back =
                    post("/api/lr/" + id + "/status", token, body("status", "BOOKED"));
            assertThat(back.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(detail(back)).contains("delivered");
        }

        @Test
        @DisplayName("a short local run may be delivered straight from booked")
        void booked_may_go_straight_to_delivered() {
            long id = created(minimal()).get("id").asLong();
            assertThat(post("/api/lr/" + id + "/status", token, body("status", "DELIVERED"))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("cancelling keeps the row, the number and the reason")
        void cancelling_keeps_everything() {
            long id = created(minimal()).get("id").asLong();

            JsonNode cancelled = post("/api/lr/" + id + "/cancel", token,
                    body("reason", "Customer called it off")).getBody();

            assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.get("cancel_reason").asText()).isEqualTo("Customer called it off");
            assertThat(cancelled.get("cancelled_at").isNull()).isFalse();
            // The row is still there and still holds its number -- the series stays continuous.
            assertThat(get("/api/lr/" + id, token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(created(minimal()).get("lr_number").asText()).isEqualTo("LR-002");
        }

        @Test
        @DisplayName("a cancelled receipt cannot be revived")
        void cancelled_is_terminal() {
            long id = created(minimal()).get("id").asLong();
            post("/api/lr/" + id + "/cancel", token, body("reason", "Mistake"));

            assertThat(post("/api/lr/" + id + "/status", token, body("status", "BOOKED"))
                    .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("a closed receipt may still be corrected — the lifecycle is what is locked")
        void editing_is_allowed_in_every_state() {
            // Correcting what a receipt SAYS and changing what has HAPPENED to it are separate
            // acts. A wrong weight is normally found when the paperwork is reconciled, which is
            // after delivery, and a register nobody can correct grows known-wrong rows.
            long delivered = created(minimal()).get("id").asLong();
            post("/api/lr/" + delivered + "/status", token, body("status", "DELIVERED"));

            Map<String, Object> fixed = minimal();
            fixed.put("consignee_name", "Bagru Cement Works Pvt Ltd");
            ResponseEntity<JsonNode> edited = put("/api/lr/" + delivered, token, fixed);

            assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(edited.getBody().get("consignee_name").asText())
                    .isEqualTo("Bagru Cement Works Pvt Ltd");
            // Still delivered: the edit said nothing about status, so nothing moved.
            assertThat(edited.getBody().get("status").asText()).isEqualTo("DELIVERED");

            // ...and an edit may not walk it back.
            Map<String, Object> rewind = minimal();
            rewind.put("status", "BOOKED");
            assertThat(put("/api/lr/" + delivered, token, rewind).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("there is no DELETE at all")
        void there_is_no_delete() {
            long id = created(minimal()).get("id").asLong();
            assertThat(delete("/api/lr/" + id, token).getStatusCode())
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("the database refuses a cancellation with no date, whoever writes it")
        void the_schema_holds_against_any_writer() {
            long id = created(minimal()).get("id").asLong();
            // Asserted in raw SQL, as SchemaInvariantsIT does: the point is that it holds
            // against a migration or a console, not only against this service.
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                    "UPDATE lorry_receipts SET status = 'CANCELLED' WHERE id = ?", id)))
                    .hasMessageContaining("ck_lr_cancelled");
        }
    }

    // ── the driver ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the driver")
    class Driver {

        @Test
        @DisplayName("linking someone who is not a driver is refused")
        void only_a_driver_may_drive() {
            long ownerId = users.create("Suresh Owner", "9811001122", UserType.OWNER).getId();

            Map<String, Object> request = minimal();
            request.put("driver_user_id", ownerId);

            ResponseEntity<JsonNode> response = create(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(detail(response)).contains("not a driver");
        }

        @Test
        @DisplayName("a driver's name and number are copied onto the receipt")
        void a_linked_driver_fills_the_document() {
            long driverId = users.create("Ramesh Kumar", "9811008120", UserType.DRIVER).getId();

            Map<String, Object> request = minimal();
            request.put("driver_user_id", driverId);
            JsonNode lr = created(request);

            assertThat(lr.get("driver_name").asText()).isEqualTo("Ramesh Kumar");
            assertThat(lr.get("driver_mobile").asText()).isEqualTo("9811008120");
        }

        @Test
        @DisplayName("a hired driver who is not on our books is still accepted")
        void an_unknown_driver_is_allowed() {
            Map<String, Object> request = minimal();
            request.put("driver_name", "Balwinder Singh");
            request.put("driver_mobile", "9812345678");
            JsonNode lr = created(request);

            assertThat(lr.get("driver_user_id").isNull()).isTrue();
            assertThat(lr.get("driver_name").asText()).isEqualTo("Balwinder Singh");
        }
    }

    // ── the register ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the register")
    class Register {

        @Test
        @DisplayName("search matches the printed text, not the master rows")
        void search_matches_the_snapshot() {
            long id = created(minimal()).get("id").asLong();
            long customerId = get("/api/lr/" + id, token).getBody().get("consignor_id").asLong();
            put("/api/customers/" + customerId, token, body("name", "Something Else Entirely"));

            // Still findable under the name that is on the paper.
            assertThat(get("/api/lr?q=Kumar", token).getBody().get("total").asInt()).isEqualTo(1);
            assertThat(get("/api/lr?q=Something", token).getBody().get("total").asInt()).isZero();
        }

        @Test
        @DisplayName("filters by status, route text and outstanding balance")
        void filters_work() {
            Map<String, Object> paid = minimal();
            paid.put("freight_charges", 1000);
            paid.put("advance", 1000);
            created(paid);

            Map<String, Object> owing = minimal();
            owing.put("freight_charges", 5000);
            owing.put("to_place", "Surat");
            long owingId = created(owing).get("id").asLong();

            assertThat(get("/api/lr?unpaid=true", token).getBody().get("total").asInt())
                    .isEqualTo(1);
            assertThat(get("/api/lr?q=Surat", token).getBody().get("total").asInt()).isEqualTo(1);

            post("/api/lr/" + owingId + "/status", token, body("status", "DELIVERED"));
            // Delivering it does NOT settle it. An earlier version scoped "unpaid" to open
            // receipts, so marking a consignment delivered made the money owed for it vanish
            // from the one total that exists to track it -- visible as a row reading
            // "25,000 owed" under a header reading "0 outstanding". Changeset 026.
            assertThat(get("/api/lr?unpaid=true", token).getBody().get("total").asInt())
                    .isEqualTo(1);
            assertThat(get("/api/lr?status=DELIVERED", token).getBody().get("total").asInt())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the header counts every state and totals what is owed")
        void totals_add_up() {
            Map<String, Object> owing = minimal();
            owing.put("freight_charges", 7000);
            owing.put("advance", 2000);
            created(owing);
            long second = created(minimal()).get("id").asLong();
            post("/api/lr/" + second + "/cancel", token, body("reason", "Duplicate"));

            JsonNode totals = get("/api/lr/totals", token).getBody();
            assertThat(totals.get("total").asInt()).isEqualTo(2);
            assertThat(totals.get("booked").asInt()).isEqualTo(1);
            assertThat(totals.get("cancelled").asInt()).isEqualTo(1);
            assertThat(totals.get("outstanding").decimalValue().intValue()).isEqualTo(5000);
        }

        @Test
        @DisplayName("an unknown sort key is refused rather than silently ignored")
        void a_bad_sort_is_a_400() {
            assertThat(get("/api/lr?sort=whatever", token).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("a start date after the end date is refused")
        void a_backwards_range_is_refused() {
            assertThat(get("/api/lr?from=2026-09-30&to=2026-09-01", token).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── access ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every route needs a token")
    void the_whole_feature_is_behind_a_login() {
        for (String path : java.util.List.of("/api/lr", "/api/lr/totals", "/api/lr/next-number",
                "/api/customers", "/api/goods-types")) {
            assertThat(rest.getForEntity(path, JsonNode.class).getStatusCode())
                    .as("anonymous GET %s", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("who may work with receipts")
    class Access {

        private static final java.util.List<String> READS =
                java.util.List.of("/api/lr", "/api/lr/totals", "/api/lr/next-number");

        @Test
        @DisplayName("a plain CSR is refused every route, including the download")
        void a_csr_may_not_touch_them() {
            long id = created(minimal()).get("id").asLong();
            String csr = signedInAs(com.vehiclemanagement.domain.UserType.STAFF,
                    "Plain Desk", "9800000044", "plaindesk");

            for (String path : READS) {
                assertThat(get(path, csr).getStatusCode())
                        .as("CSR reading %s", path).isEqualTo(HttpStatus.FORBIDDEN);
            }
            assertThat(get("/api/lr/" + id, csr).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            // The PDF too. A download of a consignment is the consignment.
            assertThat(get("/api/lr/" + id + "/pdf", csr).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            // And writing.
            assertThat(post("/api/lr", csr, minimal()).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/api/lr/" + id + "/cancel", csr, body("reason", "no"))
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a TEJJJ_CSR may do all of it")
        void the_firms_own_desk_may() {
            created(minimal());
            com.vehiclemanagement.domain.User desk = users.create(
                    "Tejjj Desk", "9800000045", com.vehiclemanagement.domain.UserType.STAFF);
            users.setLogin(desk.getId(), "tejjjdesk", PASSWORD,
                    com.vehiclemanagement.domain.UserRole.TEJJJ_CSR);
            String token = logIn("tejjjdesk", PASSWORD);

            for (String path : READS) {
                assertThat(get(path, token).getStatusCode())
                        .as("TEJJJ_CSR reading %s", path).isEqualTo(HttpStatus.OK);
            }
            assertThat(post("/api/lr", token, minimal()).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("losing the role closes the door on the next request")
        void a_demotion_shuts_it_immediately() {
            com.vehiclemanagement.domain.User desk = users.create(
                    "Tejjj Desk", "9800000045", com.vehiclemanagement.domain.UserType.STAFF);
            users.setLogin(desk.getId(), "tejjjdesk", PASSWORD,
                    com.vehiclemanagement.domain.UserRole.TEJJJ_CSR);
            String token = logIn("tejjjdesk", PASSWORD);
            assertThat(get("/api/lr", token).getStatusCode()).isEqualTo(HttpStatus.OK);

            users.setRole(desk.getId(), com.vehiclemanagement.domain.UserRole.CSR);

            // Same token, no re-issue: the authority is read from the row on every call.
            assertThat(get("/api/lr", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("the masters behind a receipt stay open to everyone")
        void the_masters_are_not_gated() {
            String csr = signedInAs(com.vehiclemanagement.domain.UserType.STAFF,
                    "Plain Desk", "9800000044", "plaindesk");

            // Customers and goods types are pick lists on the Masters screen, which is not
            // part of this. Gating them would have been scope nobody asked for.
            assertThat(get("/api/customers", csr).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/api/goods-types", csr).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
