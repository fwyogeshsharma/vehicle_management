package com.vehiclemanagement.web;

import com.vehiclemanagement.exception.FieldValidationException;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a client's {@code sort} value against a whitelist of real columns.
 *
 * <p><b>An unknown value is rejected, not ignored.</b> The obvious alternative — fall back to the
 * default ordering — means a client that sorts by {@code registraton_number} gets a page that
 * looks plausible, is ordered by something else, and says nothing about it. The bug then surfaces
 * as "the sorting doesn't work sometimes". A 400 naming the field costs one round trip and
 * ends the conversation.
 *
 * <p>The whitelist exists because these become raw SQL: the values are column names appended to
 * a native query, so anything not on the list must never reach the database.
 *
 * <p><b>Column names, which means this is only valid for a native query.</b> Spring Data
 * resolves a {@code Sort} on a <i>derived</i> query against entity property names instead, so
 * handing one of these to {@code findByStatusOrderBy...} raises a
 * {@code PropertyReferenceException} and a 500 — {@code created_at} is a column, the property is
 * {@code createdAt}. Endpoints backed by derived queries put their ordering in the method name
 * and pass {@code Sort.unsorted()}; see IntakeController.
 *
 * <p>Every ordering ends with {@code id}. Without a tiebreaker two rows with equal sort keys can
 * appear in either order between requests, which makes a row visible on page 1 and page 2 — or on
 * neither.
 */
public final class Sorts {

    private Sorts() {
    }

    /** Builder for a whitelist: client-facing name to the column it means. */
    public static Builder allowing(String name, String column) {
        return new Builder().and(name, column);
    }

    public static final class Builder {

        private final Map<String, String> allowed = new LinkedHashMap<>();
        private String defaultName;

        public Builder and(String name, String column) {
            allowed.put(name, column);
            if (defaultName == null) {
                defaultName = name;
            }
            return this;
        }

        /**
         * @param value what the client sent: a column name, optionally {@code -} prefixed for
         *              descending. Null or blank means the first whitelisted column, ascending.
         */
        public Sort resolve(String value) {
            String raw = value == null ? "" : value.trim();
            boolean descending = raw.startsWith("-");
            String name = descending ? raw.substring(1) : raw;
            if (name.isEmpty()) {
                name = defaultName;
                descending = false;
            }
            String column = allowed.get(name);
            if (column == null) {
                throw new FieldValidationException("sort",
                        "Cannot sort by \"" + name + "\". Try one of: "
                        + String.join(", ", allowed.keySet())
                        + " (prefix with - for descending).");
            }
            Sort sort = Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, column);
            return column.equals("id") ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
    }
}
