package com.vehiclemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for the tests that go over HTTP.
 *
 * <p>A real server on a random port and a real client, rather than MockMvc, so that the filter
 * chain, the JSON naming strategy and the exception handler are all genuinely in the path. A
 * MockMvc suite can pass while the deployed application returns a different shape.
 *
 * <p>Request bodies are built as maps with <b>snake_case keys written out literally</b>, and
 * responses are read as raw {@link JsonNode}. Round-tripping the DTO records instead would use
 * the same naming strategy on both sides and agree with itself no matter what the wire format
 * was — which is exactly the bug these tests exist to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTest extends DatabaseTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected UserService users;

    // ── making a caller ─────────────────────────────────────────────────────────

    protected static final String PASSWORD = "correct-horse-battery";

    /** A person with a login of the given type, and the token to call with. */
    protected String signedInAs(UserType type, String name, String mobile, String username) {
        User u = users.create(name, mobile, type);
        // An administrator administers; everybody else with a login works the desk. The
        // suite's authorisation tests turn on exactly that split.
        users.setLogin(u.getId(), username, PASSWORD,
                type == UserType.ADMIN ? UserRole.ADMIN : UserRole.CSR);
        return logIn(username, PASSWORD);
    }

    protected String admin() {
        return signedInAs(UserType.ADMIN, "Root Admin", "9800000001", "root");
    }

    protected String staff() {
        return signedInAs(UserType.STAFF, "Desk Staff", "9800000002", "desk");
    }

    /** Sign in over HTTP, so the tests use the same path a client would. */
    protected String logIn(String username, String password) {
        ResponseEntity<JsonNode> response = post("/api/auth/login", null,
                body("username", username, "password", password));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Could not sign in as " + username + ": "
                    + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody().get("token").asText();
    }

    // ── calling ─────────────────────────────────────────────────────────────────

    protected ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ResponseEntity<JsonNode> put(String path, String token, Object body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    protected ResponseEntity<JsonNode> patch(String path, String token, Object body) {
        return exchange(HttpMethod.PATCH, path, token, body);
    }

    protected ResponseEntity<JsonNode> delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String token,
                                                Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    // ── small helpers ───────────────────────────────────────────────────────────

    /** A request body, spelt the way the wire spells it. */
    protected static Map<String, Object> body(Object... keyThenValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            map.put((String) keyThenValue[i], keyThenValue[i + 1]);
        }
        return map;
    }

    /** The {@code detail} of an error body, as text, whichever of the two shapes it is in. */
    protected static String detail(ResponseEntity<JsonNode> response) {
        JsonNode detail = response.getBody().get("detail");
        return detail.isArray() ? detail.toString() : detail.asText();
    }

    protected long idOf(ResponseEntity<JsonNode> response) {
        return response.getBody().get("id").asLong();
    }
}
