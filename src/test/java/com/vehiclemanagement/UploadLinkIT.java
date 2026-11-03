package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public photo-intake page's backend: one secret link per person, and what it may do.
 *
 * <p>The properties worth pinning are the security ones -- the row is filed under the link's
 * owner whatever the request says, a replaced or revoked link and a deactivated owner all stop
 * working, and nothing else is reachable without a token.
 */
class UploadLinkIT extends ApiTest {

    private static final byte[] PHOTO = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    private String admin;
    private long fieldUserId;

    @BeforeEach
    void reset() {
        resetDomainData();
        admin = admin();
        fieldUserId = users.create("Ramesh Field", "9811100001", UserType.STAFF).getId();
    }

    private String issue(long userId) {
        ResponseEntity<JsonNode> r = post("/api/users/" + userId + "/upload-link", admin, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("token").asText();
    }

    private ResponseEntity<JsonNode> send(String token, int photos, String... fields) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < photos; i++) {
            form.add("images", new ByteArrayResource(PHOTO) {
                @Override
                public String getFilename() {
                    return "truck.jpg";
                }
            });
        }
        for (int i = 0; i < fields.length; i += 2) {
            form.add(fields[i], fields[i + 1]);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // No bearer token: that is the whole point of the page.
        return rest.exchange("/api/public/upload/" + token + "/photos",
                HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT uploaded_by, reported_by, reporter_mobile, reported_plate,"
                + " jsonb_array_length(image_keys) AS photos, processing_status"
                + " FROM vehicle_intake WHERE id = ?", id);
    }

    @Test
    @DisplayName("an entry sent through the link is filed under the link's owner")
    void the_entry_is_filed_under_the_owner() {
        String token = issue(fieldUserId);

        ResponseEntity<JsonNode> sent = send(token, 2, "reported_plate", "MH12AB1234");

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> row = row(sent.getBody().get("id").asLong());
        assertThat(row.get("uploaded_by")).isEqualTo(fieldUserId);
        assertThat(row.get("reported_by")).isEqualTo("Ramesh Field");
        assertThat(row.get("reporter_mobile")).isEqualTo("9811100001");
        assertThat(row.get("photos")).isEqualTo(2);
        assertThat(row.get("processing_status")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("photos are optional")
    void an_entry_without_photos_is_accepted() {
        ResponseEntity<JsonNode> sent = send(issue(fieldUserId), 0,
                "reported_plate", "MH12AB1234", "reported_mobile", "9822200002");

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> row = row(sent.getBody().get("id").asLong());
        assertThat(row.get("photos")).isEqualTo(0);
        assertThat(row.get("processing_status")).isEqualTo("DONE");
        assertThat(row.get("uploaded_by")).isEqualTo(fieldUserId);
    }

    @Test
    @DisplayName("owner, capacity and several places are kept, and none of them is required")
    void owner_capacity_and_places_are_saved() {
        String token = issue(fieldUserId);
        JsonNode masters = rest.getForEntity("/api/public/upload/" + token + "/masters",
                JsonNode.class).getBody();
        long nine = 0;
        for (JsonNode c : masters.get("capacities")) {
            if (c.get("tons").decimalValue().intValue() == 9) {
                nine = c.get("id").asLong();
            }
        }
        JsonNode state = masters.get("states").get(0);
        long stateId = state.get("id").asLong();
        long cityId = state.get("cities").get(0).get("id").asLong();
        String places = "[{\"state_id\":" + stateId + ",\"city_id\":" + cityId + "},"
                + "{\"state_id\":" + stateId + ",\"city_id\":null}]";

        ResponseEntity<JsonNode> sent = send(token, 0, "owner", "Kumar Roadways",
                "capacity_id", String.valueOf(nine), "places", places);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long id = sent.getBody().get("id").asLong();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reported_company, capacity_id, jsonb_array_length(edited_places) AS n"
                        + " FROM vehicle_intake WHERE id = ?", id);
        assertThat(row.get("reported_company")).isEqualTo("Kumar Roadways");
        assertThat(row.get("capacity_id")).isEqualTo(nine);
        assertThat(row.get("n")).isEqualTo(2);

        // Nothing at all is also accepted.
        assertThat(send(token, 0).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // An unknown capacity id costs that field, not the entry.
        ResponseEntity<JsonNode> bad = send(token, 0, "capacity_id", "999999");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT capacity_id FROM vehicle_intake WHERE id = ?",
                Long.class, bad.getBody().get("id").asLong())).isNull();
    }

    @Test
    @DisplayName("the pick lists need a valid link, and carry cities and capacities")
    void masters_are_behind_the_link() {
        String token = issue(fieldUserId);

        JsonNode ok = rest.getForEntity("/api/public/upload/" + token + "/masters",
                JsonNode.class).getBody();
        assertThat(ok.get("states").size()).isGreaterThan(0);
        assertThat(ok.get("capacities").size()).isGreaterThan(0);

        assertThat(rest.getForEntity("/api/public/upload/" + "x".repeat(43) + "/masters",
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        delete("/api/users/" + fieldUserId + "/upload-link", admin);
        assertThat(rest.getForEntity("/api/public/upload/" + token + "/masters",
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the sender cannot name someone else: reporter fields in the request are ignored")
    void the_request_cannot_choose_who_it_is_from() {
        ResponseEntity<JsonNode> sent = send(issue(fieldUserId), 0,
                "reported_by", "Someone Else", "reporter_mobile", "9999999999",
                "reported_plate", "MH12AB1234");

        Map<String, Object> row = row(sent.getBody().get("id").asLong());
        assertThat(row.get("reported_by")).isEqualTo("Ramesh Field");
        assertThat(row.get("reporter_mobile")).isEqualTo("9811100001");
    }

    @Test
    @DisplayName("the worklist shows who sent it")
    void the_worklist_names_the_sender() {
        long id = send(issue(fieldUserId), 0, "reported_plate", "MH12AB1234")
                .getBody().get("id").asLong();

        JsonNode item = get("/api/intake/" + id, admin).getBody();
        JsonNode summary = item.has("summary") ? item.get("summary") : item;
        assertThat(summary.get("uploaded_by_name").asText()).isEqualTo("Ramesh Field");
    }

    @Test
    @DisplayName("the page can ask whose link it is, and a bad link is a 404")
    void check_names_the_owner_and_refuses_strangers() {
        String token = issue(fieldUserId);

        ResponseEntity<JsonNode> ok = rest.getForEntity(
                "/api/public/upload/" + token, JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("name").asText()).isEqualTo("Ramesh Field");
        assertThat(ok.getBody().fieldNames().next()).isEqualTo("name");
        assertThat(ok.getBody().size()).as("nothing but the name is revealed").isEqualTo(1);

        assertThat(rest.getForEntity("/api/public/upload/" + "x".repeat(43), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send("x".repeat(43), 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("making a new link stops the old one working")
    void a_new_link_replaces_the_old() {
        String first = issue(fieldUserId);
        String second = issue(fieldUserId);

        assertThat(send(first, 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(second, 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("revoking a link stops it working")
    void revoking_ends_it() {
        String token = issue(fieldUserId);
        assertThat(delete("/api/users/" + fieldUserId + "/upload-link", admin).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(send(token, 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/users/" + fieldUserId + "/upload-link", admin)
                .getBody().get("exists").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("deactivating the person ends the link; reactivating brings it back")
    void deactivation_ends_access() {
        String token = issue(fieldUserId);
        assertThat(delete("/api/users/" + fieldUserId, admin).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(send(token, 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/users/" + fieldUserId + "/upload-link", admin, null)
                .getStatusCode()).as("cannot issue to a deactivated account")
                .isEqualTo(HttpStatus.CONFLICT);

        post("/api/users/" + fieldUserId + "/restore", admin, null);
        assertThat(send(token, 0, "reported_plate", "MH12AB1234").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("status reports use but never the token; only an administrator can manage links")
    void management_is_admin_only_and_never_reveals_the_token() {
        String token = issue(fieldUserId);
        send(token, 0, "reported_plate", "MH12AB1234");

        JsonNode status = get("/api/users/" + fieldUserId + "/upload-link", admin).getBody();
        assertThat(status.get("exists").asBoolean()).isTrue();
        assertThat(status.get("last_used_at").isNull()).isFalse();
        assertThat(status.toString()).doesNotContain(token);

        String desk = staff();
        assertThat(post("/api/users/" + fieldUserId + "/upload-link", desk, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/users/" + fieldUserId + "/upload-link", desk).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete("/api/users/" + fieldUserId + "/upload-link", desk).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/users/" + fieldUserId + "/upload-link", null, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("only the hash is stored")
    void the_token_is_not_in_the_database() {
        String token = issue(fieldUserId);

        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM upload_links WHERE user_id = ?", String.class, fieldUserId);
        assertThat(stored).hasSize(64).isNotEqualTo(token);
    }

    @Test
    @DisplayName("the rest of the API stays closed without a token")
    void nothing_else_is_open() {
        for (String path : java.util.List.of("/api/intake", "/api/vehicles", "/api/users",
                "/api/public", "/api/public/upload")) {
            assertThat(rest.getForEntity(path, JsonNode.class).getStatusCode())
                    .as(path).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        }
        // A public path must not expose the worklist through the token either.
        String token = issue(fieldUserId);
        assertThat(rest.getForEntity("/api/public/upload/" + token + "/photos", JsonNode.class)
                .getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }
}
