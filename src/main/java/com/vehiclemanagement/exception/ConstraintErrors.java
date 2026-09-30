package com.vehiclemanagement.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns a database constraint violation into something a person can act on.
 *
 * <p>This schema pushes its rules into the database deliberately, so they hold against any
 * writer. The cost is that a caller who breaks one gets
 * {@code ERROR: duplicate key value violates unique constraint "uq_vxu_primary_driver"} —
 * accurate, and useless to whoever is filling in a form. Every constraint here is explicitly
 * named, which is what makes this translation possible at all.
 *
 * <p><b>Timing is the whole trick.</b> A violation surfaces when the statement actually reaches
 * the database, and with JPA that is the flush — which, left alone, happens at commit, *after*
 * the service method has returned and any try/catch around it has gone. So callers must flush
 * inside {@link #translating}: {@code saveAndFlush} rather than {@code save}. Without that this
 * class silently never fires.
 *
 * <p>Anything not listed is rethrown untouched. A made-up friendly message for a constraint
 * nobody anticipated would hide a real bug behind reassuring words.
 */
public final class ConstraintErrors {

    /** A readable message, and the form field it belongs under — null means "not one field". */
    private record Mapping(String message, String field) {
    }

    private static final Map<String, Mapping> BY_CONSTRAINT = Map.ofEntries(
            // -- duplicates a person could plausibly create twice
            Map.entry("uq_vxu_primary_driver", new Mapping(
                    "This vehicle already has a primary driver. Clear that one first.", null)),
            // Reachable only from raw SQL. Through the services these never fire: both link
            // tables have ASSIGNED composite ids, so JpaRepository.save() merges an existing row
            // instead of inserting a duplicate -- addDriver and joinCompany are idempotent as a
            // result. See ConstraintMessagesIT.
            Map.entry("pk_vehicle_x_user", new Mapping(
                    "That person is already recorded as a driver of this vehicle.", null)),
            Map.entry("uq_uxc_primary", new Mapping(
                    "That person already has a primary company.", null)),
            Map.entry("pk_user_x_company", new Mapping(
                    "That person is already attached to this company.", null)),
            Map.entry("uq_vxl", new Mapping(
                    "That location is already on this vehicle's list.", null)),
            Map.entry("uq_cxl", new Mapping(
                    "That location is already on this company's list.", null)),
            Map.entry("uq_vehicles_reg", new Mapping(
                    "A vehicle with that registration number is already registered.",
                    "registration_number")),
            Map.entry("uq_users_mobile", new Mapping(
                    "Someone is already registered on that mobile number.", "mobile")),
            Map.entry("uq_users_username", new Mapping(
                    "That username is taken.", "username")),
            Map.entry("uq_companies_key", new Mapping(
                    "A company with that name already exists.", "name")),
            Map.entry("uq_companies_gstin", new Mapping(
                    "A company with that GSTIN already exists.", "gstin")),

            // -- the model's own rules, in the words of the rule rather than the constraint
            Map.entry("ck_vehicles_one_owner", new Mapping(
                    "A vehicle has exactly one owner: a company or a person, "
                    + "not both and not neither.", "owner")),
            Map.entry("fk_vxu_employed", new Mapping(
                    "Only a company's own drivers may be assigned to its vehicles. "
                    + "Add this person to the company first.", null)),
            Map.entry("fk_vxu_vehicle_company", new Mapping(
                    "That driver record names the wrong company for this vehicle.", null)),
            Map.entry("fk_vxu_vehicle_owned", new Mapping(
                    "This vehicle's ownership has changed; clear its driver assignments first.",
                    null)),
            Map.entry("ck_vxu_company_present", new Mapping(
                    "A driver record must name the owning company for a company vehicle, "
                    + "and none for a personally-owned one.", null)),
            // fk_vxl_vehicle is a plain FK to vehicles(id) since changeset 009 and can now only
            // fire on a vehicle that does not exist -- which the service checks first. The old
            // mapping said "a company vehicle runs where the company runs", which stopped being
            // true when a truck gained the right to its own locations alongside its company's.
            Map.entry("fk_vxl_vehicle", new Mapping(
                    "That vehicle no longer exists.", null)),
            Map.entry("fk_vxl_city_in_state", new Mapping(
                    "That city is not in the state you named.", "city_id")),
            Map.entry("fk_cxl_city_in_state", new Mapping(
                    "That city is not in the state you named.", "city_id")),
            Map.entry("ck_users_login_pair", new Mapping(
                    "A login needs both a username and a password, or neither.", "username")),

            // -- physically impossible vehicles
            Map.entry("ck_vehicles_axles", new Mapping(
                    "That is not a plausible number of axles.", "no_of_axles")),
            Map.entry("ck_vehicles_wheels", new Mapping(
                    "That is not a plausible number of wheels; it must also be even.",
                    "no_of_wheels")),
            Map.entry("ck_vehicles_length", new Mapping(
                    "That is not a plausible length in feet.", "length_ft")),
            Map.entry("ck_vehicles_reg_canon", new Mapping(
                    "Registration numbers are stored upper-case with no spaces or dashes.",
                    "registration_number")),
            Map.entry("ck_users_mobile", new Mapping(
                    "Enter a valid 10-digit Indian mobile number (starting with 6-9).", "mobile")),
            Map.entry("ck_users_alt_mobile", new Mapping(
                    "Enter a valid 10-digit Indian mobile number (starting with 6-9).",
                    "alt_mobile")),
            Map.entry("ck_companies_mobile", new Mapping(
                    "Enter a valid 10-digit Indian mobile number (starting with 6-9).", "mobile")),
            Map.entry("ck_companies_gstin", new Mapping(
                    "That does not look like a GSTIN, e.g. 27AAPFU0939F1ZV.", "gstin")));

    private ConstraintErrors() {
    }

    /**
     * Runs {@code work}, translating a recognised constraint violation on the way out.
     *
     * <p>{@code work} must reach the database before it returns — use {@code saveAndFlush}, not
     * {@code save}. See the class note on timing.
     */
    public static <T> T translating(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * The mapped exception, or the original if this constraint is not one we anticipated.
     *
     * <p>Returns rather than throws so the caller keeps its own stack trace, and so an
     * unrecognised violation propagates exactly as it would have.
     */
    public static RuntimeException translate(DataIntegrityViolationException e) {
        Mapping mapping = BY_CONSTRAINT.get(constraintName(e));
        if (mapping == null) {
            return e;
        }
        return mapping.field() == null
                ? new ApiException.Conflict(mapping.message())
                : new FieldValidationException(mapping.field(), mapping.message());
    }

    /**
     * The constraint's name, or null.
     *
     * <p>Hibernate parses it off the driver's error for us. The fallback scans the message text
     * because a statement issued outside JPA — a JdbcTemplate call, say — arrives without a
     * Hibernate exception in the chain.
     */
    private static String constraintName(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                // Postgres may qualify it as "public.uq_foo"
                String name = cve.getConstraintName();
                return name.substring(name.lastIndexOf('.') + 1);
            }
        }
        String message = e.getMostSpecificCause().getMessage();
        if (message != null) {
            for (String candidate : BY_CONSTRAINT.keySet()) {
                if (message.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
