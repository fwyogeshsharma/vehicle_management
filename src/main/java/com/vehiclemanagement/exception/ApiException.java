package com.vehiclemanagement.exception;

/**
 * Base class for errors that map straight to an HTTP status.
 *
 * <p>Deliberately carries a plain {@code int} status rather than Spring's {@code HttpStatus}:
 * the services that throw these stay usable from a seeder, a batch import or a test without
 * dragging spring-web into them. {@code GlobalExceptionHandler} is the single place that turns
 * the status into a response.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static final class NotFound extends ApiException {
        public NotFound(String message) {
            super(404, message);
        }
    }

    /** A rule the caller could not have known it was breaking: a duplicate, or a clash. */
    public static final class Conflict extends ApiException {
        public Conflict(String message) {
            super(409, message);
        }
    }

    /**
     * Bad or missing credentials. Deliberately says nothing about which: an unknown username and
     * a wrong password must be indistinguishable, or the endpoint enumerates accounts.
     */
    public static final class Unauthorized extends ApiException {
        public Unauthorized(String message) {
            super(401, message);
        }
    }

    public static final class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(400, message);
        }
    }
}
