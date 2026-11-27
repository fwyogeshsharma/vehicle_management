package com.vehiclemanagement.web;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Words a field error so it names the input and the value that was refused:
 * {@code Driver mobile "12345": Enter a valid 10-digit Indian mobile number (starting with 6-9).}
 *
 * <p>One definition, so every screen and the mobile app read the same way, and a new rule gets
 * the treatment without its author remembering to.
 */
final class FieldMessages {

    /** Names that do not read well when derived from the wire name. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("gstin", "GSTIN"),
            Map.entry("lr_number", "LR number"),
            Map.entry("lr_date", "Date"),
            Map.entry("no_of_axles", "Axles"),
            Map.entry("no_of_wheels", "Wheels"),
            Map.entry("length_ft", "Length (ft)"),
            Map.entry("weight_kg", "Weight (kg)"),
            Map.entry("weight_tons", "Weight (tons)"),
            Map.entry("tons", "Tonnage"),
            Map.entry("alt_mobile", "Alternate mobile"),
            Map.entry("driver_alt_mobile", "Driver's other mobile"),
            Map.entry("driver_mobile", "Driver mobile"),
            Map.entry("reported_mobile", "Mobile number"),
            Map.entry("reported_plate", "Registration number"),
            Map.entry("plate", "Registration number"),
            Map.entry("owner", "Driver / owner name"),
            Map.entry("consignor_id", "Customer"),
            Map.entry("commission_percentage", "Commission (%)"));

    /** Never repeated back: secrets, and text too long to be worth quoting. */
    private static final Set<String> VALUE_WITHHELD = Set.of("notes", "images", "places", "sort", "tz");

    private static final int LONGEST_QUOTE = 40;

    /** Shorter than this, a value turning up in a message is chance: "1" is in "10-digit". */
    private static final int SHORTEST_SELF_NAMING = 4;

    private FieldMessages() {
    }

    /** "driver_mobile" becomes "Driver mobile"; "places[0].price" becomes "Price (location 1)". */
    static String label(String field) {
        int dot = field.lastIndexOf('.');
        if (dot >= 0) {
            String parent = field.substring(0, dot);
            String leaf = label(field.substring(dot + 1));
            int open = parent.indexOf('[');
            if (parent.startsWith("places") && open >= 0) {
                int index = Integer.parseInt(parent.substring(open + 1, parent.indexOf(']')));
                return leaf + " (location " + (index + 1) + ")";
            }
            return leaf;
        }
        String known = LABELS.get(field);
        if (known != null) {
            return known;
        }
        String words = (field.endsWith("_id") ? field.substring(0, field.length() - 3) : field)
                .replace('_', ' ');
        return words.isEmpty() ? "This value"
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * The value in quotes, or null when there is nothing worth showing: none sent, a password, an
     * internal id (a number the user never typed), or long free text.
     */
    static String quoted(String field, String value) {
        if (value == null || value.isBlank() || field.contains("password")
                || field.endsWith("_id") || VALUE_WITHHELD.contains(field)) {
            return null;
        }
        String shown = value.strip().replaceAll("\\s+", " ");
        if (shown.length() > LONGEST_QUOTE) {
            shown = shown.substring(0, LONGEST_QUOTE) + "…";
        }
        return "\"" + shown + "\"";
    }

    /**
     * A number as the person typed it: 500, not {@code 5E+2}. An absurd exponent is left in
     * scientific form rather than expanded into millions of digits.
     */
    static String plain(BigDecimal number) {
        return Math.abs((long) number.scale()) > LONGEST_QUOTE
                ? number.toString() : number.toPlainString();
    }

    /** True when the message already names this value as a whole word. */
    private static boolean alreadyNames(String message, String value) {
        return value.length() >= SHORTEST_SELF_NAMING && Pattern
                .compile("(?<!\\p{Alnum})" + Pattern.quote(value) + "(?!\\p{Alnum})")
                .matcher(message).find();
    }

    /**
     * {@code message} prefixed with the field and value. Left alone when there is no value to
     * quote (the message already says what is missing) or when it already quotes it.
     */
    static String describe(String field, String value, String message) {
        String shown = quoted(field, value);
        if (shown == null || alreadyNames(message, value.strip())) {
            return message;
        }
        return label(field) + " " + shown + ": " + message;
    }
}
