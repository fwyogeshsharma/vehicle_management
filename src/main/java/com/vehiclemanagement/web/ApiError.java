package com.vehiclemanagement.web;

import java.util.List;

/**
 * The single error shape this API returns, in both its forms.
 *
 * <p>A message that is not about one field:
 * <pre>{"detail": "Vehicle 7 not found."}</pre>
 *
 * <p>A message that belongs under one input, so a form can render it in the right place:
 * <pre>{"detail": [{"loc": ["body", "registration_number"], "msg": "..."}]}</pre>
 *
 * <p>{@code loc} names the snake_case field the client sent, not the Java property. Everything
 * that produces an error — {@code GlobalExceptionHandler}, and the 401/403 handlers in
 * {@code SecurityConfig} — goes through here, so there is one shape to parse rather than one per
 * failure mode.
 */
public record ApiError(Object detail) {

    /**
     * The two security refusals, worded once.
     *
     * <p>They are produced from two places — Spring Security's entry point for a request that
     * never reaches a controller, and {@code GlobalExceptionHandler} for a {@code @PreAuthorize}
     * that fails inside one. Two wordings for one condition is how a client ends up matching on
     * a string that only appears half the time.
     */
    public static final String NOT_SIGNED_IN = "Sign in to use this.";

    public static final String NOT_ALLOWED = "You do not have access to this.";

    public static ApiError of(String message) {
        return new ApiError(message);
    }

    public static ApiError field(String field, String message) {
        return new ApiError(List.of(new FieldError(List.of("body", field), message)));
    }

    public static ApiError fields(List<FieldError> errors) {
        return new ApiError(errors);
    }

    /** One field's complaint. {@code loc} is a path so nested bodies stay expressible later. */
    public record FieldError(List<String> loc, String msg) {
    }
}
