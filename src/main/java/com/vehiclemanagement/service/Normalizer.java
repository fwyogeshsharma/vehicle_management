package com.vehiclemanagement.service;

import com.vehiclemanagement.exception.FieldValidationException;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input clean-up rules shared by the services. Failures surface as a field-keyed 422.
 *
 * <p>Ported from the TTS application, where these rules are already proven against real Indian
 * transport data. Two things here are deliberately NOT also database constraints:
 *
 * <ul>
 *   <li><b>The registration format.</b> The database checks only that what is stored is
 *       canonical ({@code ^[A-Z0-9]{5,12}$}), which is what makes the unique index and plate
 *       search mean anything. The full format lives here because it is a business rule that
 *       will change: {@link #REGISTRATION} rejects the BH series ({@code 22BH1234AA}), which is
 *       on the road today. As a CHECK that is a migration on a live database; here it is a
 *       commit.
 *   <li><b>Capacity.</b> Free text by decision — no enum, no master. {@link #capacityTons}
 *       reads a number out of it for sorting only, and the database derives the stored column
 *       itself so the two cannot disagree.
 * </ul>
 */
public final class Normalizer {

    private static final Pattern NON_DIGIT = Pattern.compile("\\D+");
    private static final Pattern REG_SEPARATORS = Pattern.compile("[\\s\\-]+");
    private static final Pattern REGISTRATION = Pattern.compile("[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{1,4}");
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,49}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /**
     * Mirrors the {@code capacity_tons} generated column in 001-init-schema.sql.
     *
     * <p>Two copies of one rule, in two languages, which is a drift risk — so
     * {@code SchemaInvariantsIT.DerivedColumns.the_java_and_sql_capacity_rules_agree} runs the
     * same inputs through both and compares. Change one and that test fails.
     */
    private static final Pattern LEADING_NUMBER = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    public static final int MIN_PASSWORD_LENGTH = 8;

    private Normalizer() {
    }

    /** Trim, collapse runs of whitespace to one space; empty becomes null. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = WHITESPACE.matcher(value.trim()).replaceAll(" ");
        return v.isEmpty() ? null : v;
    }

    /** Indian mobile to 10 digits. Accepts +91 / 91 / 0 prefixes, spaces and dashes. */
    public static String mobile(String raw, String field) {
        String digits = NON_DIGIT.matcher(raw == null ? "" : raw).replaceAll("");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10 || "6789".indexOf(digits.charAt(0)) < 0) {
            throw new FieldValidationException(field,
                    "Enter a valid 10-digit Indian mobile number (starting with 6-9).");
        }
        return digits;
    }

    /** Blank becomes null, otherwise validated like {@link #mobile}. */
    public static String optionalMobile(String raw, String field) {
        return raw == null || raw.isBlank() ? null : mobile(raw, field);
    }

    /**
     * Vehicle registration to its canonical spelling: upper-case, separators stripped.
     *
     * <p>{@code field} names the input the error belongs to. TTS hard-coded
     * {@code "registration_number"}, which put the message on a field some forms do not render;
     * an error nobody sees is not an error.
     */
    public static String registration(String raw, String field) {
        String value = REG_SEPARATORS.matcher(raw == null ? "" : raw.toUpperCase()).replaceAll("");
        if (!REGISTRATION.matcher(value).matches()) {
            throw new FieldValidationException(field,
                    "Enter a valid registration number, e.g. MH12AB1234.");
        }
        return value;
    }

    public static String registration(String raw) {
        return registration(raw, "registration_number");
    }

    /** Blank becomes null, otherwise validated like {@link #registration(String, String)}. */
    public static String optionalRegistration(String raw, String field) {
        return raw == null || raw.isBlank() ? null : registration(raw, field);
    }

    public static String username(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (!USERNAME.matcher(value).matches()) {
            throw new FieldValidationException("username",
                    "Username: 3-50 chars, letters/digits/._- only.");
        }
        return value;
    }

    public static void password(String value, String field) {
        if (value == null || value.length() < MIN_PASSWORD_LENGTH) {
            throw new FieldValidationException(field,
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    /**
     * The tonnage buried in a free-text capacity, or null when there is none.
     *
     * <p>Never throws: "no idea" is a capacity someone typed, not a request to reject. This
     * mirrors the {@code capacity_tons} generated column exactly — the column is the one that
     * matters, this exists so the service can range-check a value before the insert.
     */
    public static BigDecimal capacityTons(String capacity) {
        if (capacity == null) {
            return null;
        }
        Matcher m = LEADING_NUMBER.matcher(capacity);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }
}
