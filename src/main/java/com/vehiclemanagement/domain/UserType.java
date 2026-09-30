package com.vehiclemanagement.domain;

/**
 * What a person is, for filtering the directory.
 *
 * <p>This is a label on the person, for filtering the directory. It is NOT how the system knows
 * who owns or drives a particular vehicle — that is {@code vehicles.owner_company_id} /
 * {@code owner_user_id} for ownership and {@link VehicleUser} for driving. Do not tie this
 * column to either with a constraint: it would create a second source of truth and make an
 * owner-operator unrepresentable halfway through an edit.
 *
 * <p>Known limit: one value per person, so someone who owns Company A and drives for Company B
 * is just OWNER, with nothing recording that the ownership is of A. When a second company shows
 * up against one person, the fix is an additive {@code role} column on user_x_company.
 */
public enum UserType {
    DRIVER, OWNER, BOTH, STAFF, ADMIN
}
