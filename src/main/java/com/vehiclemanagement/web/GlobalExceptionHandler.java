package com.vehiclemanagement.web;

import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.FieldValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns the exceptions the services throw into the one error shape in {@link ApiError}.
 *
 * <p><b>The catch-all matters more than it looks.</b> {@code ConstraintErrors} deliberately
 * rethrows any database constraint it does not recognise, untouched — the right call for a
 * library, because inventing friendly words for a rule nobody anticipated hides a real bug
 * behind reassuring prose. Over HTTP that same exception would put raw PostgreSQL text,
 * constraint names and column values on the wire. So the last handler here logs everything and
 * returns nothing: the detail goes where an operator can read it, not to whoever provoked it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper json;

    public GlobalExceptionHandler(ObjectMapper json) {
        this.json = json;
    }

    /**
     * The service-layer exceptions, which already carry their own status.
     *
     * <p>{@code ApiException} holds a plain {@code int} rather than an {@code HttpStatus} so the
     * services stay callable from a seeder or a test without spring-web. This is the one place
     * that translation happens.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiException(ApiException e) {
        return ResponseEntity.status(HttpStatus.valueOf(e.getStatus()))
                .body(ApiError.of(e.getMessage()));
    }

    /** 422 rather than 400: the request was understood, one of its values was not acceptable. */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiError> fieldValidation(FieldValidationException e,
                                                    HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity().body(ApiError.field(e.getField(),
                FieldMessages.describe(e.getField(), sentValue(request, e.getField()),
                        e.getMessage())));
    }

    /** Bean Validation on a request body, rendered in exactly the same shape. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> {
                    String field = snakeCase(f.getField());
                    return new ApiError.FieldError(List.of("body", field),
                            describeBeanError(field, f));
                })
                .toList();
        return ResponseEntity.unprocessableEntity().body(ApiError.fields(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException e) {
        List<ApiError.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(
                        List.of("query", lastNode(v.getPropertyPath().toString())),
                        v.getMessage()))
                .toList();
        return ResponseEntity.unprocessableEntity().body(ApiError.fields(errors));
    }

    /** A path or query value of the wrong type — {@code /api/vehicles/banana}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiError.of(
                "\"" + e.getValue() + "\" is not a valid value for " + e.getName() + "."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(e.getParameterName() + " is required."));
    }

    /** Malformed JSON, or a body where none was sent. Says so without echoing the payload. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        // A value of the wrong kind in one field is that field's error, not a broken request.
        if (e.getCause() instanceof JsonMappingException mapping) {
            String field = fieldOf(mapping);
            if (field != null) {
                return ResponseEntity.unprocessableEntity()
                        .body(ApiError.field(field, describeUnreadable(field, mapping)));
            }
        }
        return ResponseEntity.badRequest().body(ApiError.of("The request body could not be read."));
    }

    /** Over the server's size limit, which is checked before any controller runs. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiError.of(
                "The photos are too large to upload. Use smaller or fewer photos."));
    }

    /**
     * A path that maps to nothing.
     *
     * <p><b>Both types matter.</b> Spring Boot 3 raises {@code NoResourceFoundException} for an
     * unmapped path by default, and {@code NoHandlerFoundException} only when
     * {@code throw-exception-if-no-handler-found} is on. Handling just the latter leaves the
     * former to the catch-all below, which reports a **500** — so a client with a typo in a URL
     * is told the server broke rather than that the path does not exist, and goes looking in the
     * wrong place. A misspelt path in this app's own API client is exactly how that was found.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("No such endpoint."));
    }

    /**
     * The path exists; that verb does not.
     *
     * <p>Same failure mode as {@link #notFound} and found the same way: without this the
     * catch-all reports a <b>500</b>, so a client calling DELETE on something that only takes
     * GET and PUT is told the server broke. It is a 405, and the {@code Allow} header naming
     * the methods that DO work is the whole point of that status.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null && !e.getSupportedHttpMethods().isEmpty()) {
            response.allow(e.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return response.body(ApiError.of(
                e.getMethod() + " is not allowed here."));
    }

    /**
     * A {@code @PreAuthorize} that failed inside a controller.
     *
     * <p><b>This handler is not optional.</b> A {@code @RestControllerAdvice} runs before Spring
     * Security's {@code ExceptionTranslationFilter} ever sees the exception, so without it the
     * catch-all below turns every authorisation failure into a 500 — the endpoint looks broken
     * rather than guarded, and the client cannot tell "you may not" from "it crashed".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(ApiError.NOT_ALLOWED));
    }

    /** Same reasoning as {@link #accessDenied}, for a failure to identify the caller at all. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> notAuthenticated(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(ApiError.NOT_SIGNED_IN));
    }

    /**
     * Everything else, including an unmapped database constraint.
     *
     * <p>Logged in full, reported as nothing. See the class comment: the alternative is putting
     * the schema on the wire.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception serving a request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("Something went wrong. The details are in the server log."));
    }

    /** What the request carried for {@code field}: from the JSON body, else a form parameter. */
    private String sentValue(HttpServletRequest request, String field) {
        Object body = request.getAttribute(RequestBodyMemory.ATTRIBUTE);
        if (body == null) {
            return request.getParameter(field);
        }
        try {
            JsonNode value = json.valueToTree(body).get(field);
            if (value == null || !value.isValueNode() || value.isNull()) {
                return null;
            }
            return value.isNumber() ? FieldMessages.plain(value.decimalValue()) : value.asText();
        } catch (IllegalArgumentException unserialisable) {
            // Quoting the value is a courtesy; failing to must not replace the real error.
            return null;
        }
    }

    private static String describeBeanError(String field,
                                            org.springframework.validation.FieldError error) {
        ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
        Object refused = error.getRejectedValue();
        String rejected = refused == null ? null
                : refused instanceof BigDecimal number ? FieldMessages.plain(number)
                : String.valueOf(refused);
        // A message written for the field says it better than anything composed here.
        if (!violation.getMessageTemplate().startsWith("{jakarta.validation")) {
            return FieldMessages.describe(field, rejected, error.getDefaultMessage());
        }
        String label = FieldMessages.label(field);
        String quoted = FieldMessages.quoted(field, rejected);
        String subject = quoted == null ? label : label + " " + quoted;
        Map<String, Object> rule = violation.getConstraintDescriptor().getAttributes();
        return switch (String.valueOf(error.getCode())) {
            case "NotBlank", "NotNull", "NotEmpty" -> label + " is required.";
            case "Size" -> subject + " is too long: " + (rejected == null ? 0 : rejected.length())
                    + " characters, at most " + rule.get("max") + ".";
            case "DecimalMax" -> subject + " is too large: at most " + rule.get("value") + ".";
            case "Email" -> subject + " is not a valid email address.";
            default -> subject + " " + error.getDefaultMessage() + ".";
        };
    }

    /** The wire name of the innermost field Jackson was reading, or null. */
    private static String fieldOf(JsonMappingException e) {
        String field = null;
        for (JsonMappingException.Reference step : e.getPath()) {
            if (step.getFieldName() != null) {
                field = step.getFieldName();
            }
        }
        return field == null ? null : snakeCase(field);
    }

    private static String describeUnreadable(String field, JsonMappingException e) {
        String label = FieldMessages.label(field);
        if (e.getCause() instanceof InputCoercionException) {
            return label + " is out of range.";
        }
        if (!(e instanceof InvalidFormatException bad)) {
            return label + " has a value that cannot be used.";
        }
        String quoted = FieldMessages.quoted(field, String.valueOf(bad.getValue()));
        String subject = quoted == null ? label : label + " " + quoted;
        Class<?> wanted = bad.getTargetType();
        if (wanted.isEnum()) {
            return subject + " is not one of: " + Arrays.stream(wanted.getEnumConstants())
                    .map(String::valueOf).collect(Collectors.joining(", ")) + ".";
        }
        if (wanted == LocalDate.class) {
            return subject + " is not a valid date (use YYYY-MM-DD).";
        }
        if (Number.class.isAssignableFrom(wanted) || wanted.isPrimitive()) {
            return subject + " is not a valid number.";
        }
        return subject + " is not a valid value.";
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * Bean Validation reports the Java property name; the client sent snake_case.
     *
     * <p>Jackson's SNAKE_CASE strategy renames the field on the way in and out but does not touch
     * the violation, so without this a form looks the message up under "registrationNumber" and
     * finds no input by that name. An error nobody can display is not an error.
     */
    private static String snakeCase(String property) {
        StringBuilder out = new StringBuilder(property.length() + 4);
        for (int i = 0; i < property.length(); i++) {
            char c = property.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
