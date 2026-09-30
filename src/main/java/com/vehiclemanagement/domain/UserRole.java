package com.vehiclemanagement.domain;

/**
 * What a person may do in this system.
 *
 * <p><b>Not {@link UserType}.</b> That says what somebody <i>is</i> — driver, owner, office
 * staff — and the vehicle model keys off it. This says what they may <i>do</i>, and only
 * exists for the minority of rows that can sign in at all. A driver has no username, no
 * password and no role; most of the {@code users} table is drivers.
 *
 * <p>Every request resolves the caller's granted authority from this, as
 * {@code ROLE_ + name()}. Before this enum the authority came from {@code user_type}, which
 * meant the answer to "what is this person" and "what may they do" were the same column and
 * neither could change without the other.
 */
public enum UserRole {

    /** Administers accounts: grants and removes logins, changes roles, deactivates people. */
    ADMIN,

    /** Works the intake worklist, writes lorry receipts, records lanes. */
    CSR,

    /**
     * A CSR of the operating firm.
     *
     * <p><b>Carries the same permissions as {@link #CSR} today.</b> The distinction is
     * recorded so it can be acted on once somebody says what it should mean; giving it a
     * different set of rights here would be inventing a rule nobody asked for, and a rule
     * invented in an enum is one nobody can find later.
     */
    TEJJJ_CSR;

    /** Whether this role administers accounts. The only permission difference that exists. */
    public boolean administers() {
        return this == ADMIN;
    }
}
