package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** The administrator's "who added how many entries, on which days" report. */
class IntakeActivityIT extends ApiTest {

    private String admin;
    private long ramesh;
    private long mahesh;

    @BeforeEach
    void reset() {
        resetDomainData();
        admin = admin();
        ramesh = users.create("Ramesh Field", "9811100001", UserType.STAFF).getId();
        mahesh = users.create("Mahesh Field", "9811100002", UserType.STAFF).getId();
    }

    /** One entry filed under {@code user}, stamped at the given Indian-time moment. */
    private long entry(long user, String istTimestamp) {
        jdbc.update("INSERT INTO vehicle_intake (image_keys, processing_status, uploaded_by,"
                + " reported_plate, created_at) VALUES ('[]'::jsonb, 'DONE', ?, ?,"
                + " CAST(? AS timestamp) AT TIME ZONE 'Asia/Kolkata')",
                user, "MH12AB" + (1000 + (int) (Math.random() * 8999)), istTimestamp);
        return jdbc.queryForObject("SELECT max(id) FROM vehicle_intake", Long.class);
    }

    @Test
    @DisplayName("lists uploaders with totals, and counts per day in Indian time")
    void totals_and_daily_counts() {
        entry(ramesh, "2026-11-10 09:00:00");
        entry(ramesh, "2026-11-10 23:30:00");   // still the 10th in India (18:00 UTC)
        entry(ramesh, "2026-11-11 00:20:00");   // the 11th in India, though 18:50 UTC on the 10th
        entry(mahesh, "2026-11-10 10:00:00");

        JsonNode uploaders = get("/api/intake/activity/uploaders", admin).getBody();
        assertThat(uploaders).hasSize(2);
        assertThat(uploaders.get(0).get("name").asText()).isEqualTo("Mahesh Field");
        assertThat(uploaders.get(0).get("total").asInt()).isEqualTo(1);
        assertThat(uploaders.get(1).get("name").asText()).isEqualTo("Ramesh Field");
        assertThat(uploaders.get(1).get("total").asInt()).isEqualTo(3);

        JsonNode daily = get("/api/intake/activity/daily?user_id=" + ramesh, admin).getBody();
        assertThat(daily.get("total").asInt()).isEqualTo(3);
        assertThat(daily.get("days")).hasSize(2);
        assertThat(daily.get("days").get(0).get("date").asText()).isEqualTo("2026-11-11");
        assertThat(daily.get("days").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(daily.get("days").get(1).get("date").asText()).isEqualTo("2026-11-10");
        assertThat(daily.get("days").get(1).get("count").asInt()).isEqualTo(2);

        // The same instants cut in UTC fall on different days.
        JsonNode utc = get("/api/intake/activity/daily?user_id=" + ramesh + "&tz=UTC", admin).getBody();
        assertThat(utc.get("days")).hasSize(1);
        assertThat(utc.get("days").get(0).get("count").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("shows every entry for one user on one day, and nobody else's")
    void entries_for_a_day() {
        long a = entry(ramesh, "2026-11-10 09:00:00");
        long b = entry(ramesh, "2026-11-10 15:00:00");
        entry(ramesh, "2026-11-11 09:00:00");
        entry(mahesh, "2026-11-10 10:00:00");

        JsonNode rows = get("/api/intake/activity/entries?user_id=" + ramesh + "&date=2026-11-10",
                admin).getBody();

        assertThat(rows).hasSize(2);
        // Newest first.
        assertThat(rows.get(0).get("id").asLong()).isEqualTo(b);
        assertThat(rows.get(1).get("id").asLong()).isEqualTo(a);
        assertThat(rows.get(0).get("uploaded_by_name").asText()).isEqualTo("Ramesh Field");
        assertThat(get("/api/intake/activity/entries?user_id=" + ramesh + "&date=2026-11-12",
                admin).getBody()).isEmpty();
    }

    @Test
    @DisplayName("only an administrator may see it; a bad time zone is a field error")
    void admin_only() {
        var desk = users.create("Desk Staff", "9800000002", UserType.STAFF);
        users.setLogin(desk.getId(), "desk", "Desk-pass-12345", UserRole.CSR);
        String deskToken = logIn("desk", "Desk-pass-12345");

        for (String path : new String[]{"/uploaders", "/daily?user_id=" + ramesh,
                "/entries?user_id=" + ramesh + "&date=2026-11-10"}) {
            assertThat(get("/api/intake/activity" + path, deskToken).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }

        ResponseEntity<JsonNode> bad = get("/api/intake/activity/daily?user_id=" + ramesh
                + "&tz=Nowhere/Land", admin);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(bad.getBody().toString()).contains("tz");
    }
}
