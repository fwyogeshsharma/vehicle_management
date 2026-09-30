package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI is a separate project on its own origin, so every call it makes is preflighted.
 *
 * <p>Two failures this catches, both of which present to the browser as an indistinguishable
 * "CORS error" and send whoever debugs them to the wrong file: a preflight that is authenticated
 * (and so answered with 401), and an allowed-origins list that does not actually restrict
 * anything.
 */
class CorsIT extends ApiTest {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String NOT_ALLOWED = "http://localhost:5174";

    private ResponseEntity<Void> preflight(String origin, String method, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.valueOf(method));
        headers.setAccessControlRequestHeaders(java.util.List.of("authorization", "content-type"));
        return rest.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);
    }

    @Test
    @DisplayName("a preflight from the configured origin passes WITHOUT a token")
    void the_preflight_is_not_authenticated() {
        ResponseEntity<Void> response = preflight(ALLOWED, "PUT", "/api/vehicles/1");

        // If this were 401, the browser would report a CORS failure and the CORS config would
        // be the first place anyone looked -- where nothing is wrong.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(response.getHeaders().getAccessControlAllowMethods())
                .contains(HttpMethod.PUT, HttpMethod.DELETE);
        // Echoed back in the case the CLIENT sent them -- header names are case-insensitive, so
        // asserting on the capitalisation would be testing the test.
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .anyMatch("authorization"::equalsIgnoreCase);
    }

    @Test
    void an_origin_that_is_not_on_the_list_is_refused() {
        ResponseEntity<Void> response = preflight(NOT_ALLOWED, "GET", "/api/vehicles");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("the actual request carries the origin header back")
    void a_real_call_from_the_allowed_origin_works() {
        resetDomainData();
        String token = staff();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setOrigin(ALLOWED);
        ResponseEntity<JsonNode> response = rest.exchange("/api/vehicles", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("credentials are not allowed, because nothing rides on a cookie")
    void the_response_does_not_invite_cookies() {
        ResponseEntity<Void> response = preflight(ALLOWED, "GET", "/api/vehicles");

        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }
}
