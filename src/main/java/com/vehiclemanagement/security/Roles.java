package com.vehiclemanagement.security;

/**
 * The authorities this API guards with, as constants.
 *
 * <p><b>Why constants and not string literals in the annotations.</b> A misspelt authority in
 * {@code @PreAuthorize("hasRole('ADMN')")} is still a valid SpEL expression — it simply never
 * matches, so the endpoint becomes unreachable rather than unguarded, and the failure looks like
 * a permissions bug rather than a typo. Spelt the other way round (a guard dropped entirely) it
 * fails silently open. Referencing a constant makes both a compile error.
 *
 * <p>The roles are {@code ADMIN}, {@code CSR} and {@code TEJJJ_CSR}, resolved from
 * {@code users.role} — see {@link com.vehiclemanagement.domain.UserRole}. There is still no
 * permission vocabulary ({@code vehicles:write} and the like): the rule remains that a login is
 * enough for the domain and administering logins takes {@link #ADMIN}. CSR and TEJJJ_CSR are
 * therefore equivalent in what they may do, and are distinguished in the data so that the
 * distinction can be acted on when somebody defines it.
 */
public final class Roles {

    /** Matches {@code hasRole('ADMIN')}, i.e. the granted authority {@code ROLE_ADMIN}. */
    public static final String ADMIN = "hasRole('ADMIN')";

    /**
     * Who may work with lorry receipts: the operating firm's own desk, and administrators.
     *
     * <p><b>This is the one thing that tells CSR and TEJJJ_CSR apart.</b> Everything else in
     * the domain is open to any login, so this constant is the whole of the difference — if a
     * second distinction ever appears, it belongs beside this one rather than inlined into an
     * annotation where nobody will find it.
     */
    public static final String LORRY_RECEIPTS = "hasAnyRole('ADMIN', 'TEJJJ_CSR')";

    /** The authority prefix Spring Security's {@code hasRole} implies. */
    public static final String PREFIX = "ROLE_";

    private Roles() {
    }
}
