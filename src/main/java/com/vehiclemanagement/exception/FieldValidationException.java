package com.vehiclemanagement.exception;

/**
 * A validation failure tied to one named request field.
 *
 * <p>The field name is the snake_case one the client sends, not the Java property, so a form
 * can look the message up under the right input with no mapping. The REST increment renders
 * this as HTTP 422 with {@code {"detail": [{"loc": ["body", field], "msg": message}]}}.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }

    @Override
    public String toString() {
        return "FieldValidationException[" + field + ": " + getMessage() + "]";
    }
}
