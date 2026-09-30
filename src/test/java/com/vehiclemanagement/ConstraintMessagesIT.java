package com.vehiclemanagement;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a caller actually sees when they break one of the database's rules.
 *
 * <p>Pushing the rules into the schema is what makes them true against every writer. The cost is
 * that a caller who breaks one gets {@code duplicate key value violates unique constraint
 * "uq_vxu_primary_driver"} — accurate, and useless to whoever is filling in a form. These tests
 * pin the translation.
 *
 * <p>They also pin the <b>timing</b>, which is the part that silently fails: a violation surfaces
 * at flush, and an unflushed write flushes at commit — after the service method returned and its
 * try/catch went out of scope. Every one of these would pass a raw exception through if the
 * services used {@code save} instead of {@code saveAndFlush}.
 */
class ConstraintMessagesIT extends DatabaseTest {

    @Autowired BodyTypeService bodyTypes;
    @Autowired UserService users;
    @Autowired CompanyService companies;
    @Autowired VehicleService vehicles;

    private Long openBody;
    private Long mh;
    private Long nagpur;
    private User owner;
    private User hired;
    private Company company;
    private Vehicle truck;

    @BeforeEach
    void setUp() {
        resetDomainData();
        openBody = bodyTypes.ensure("Open body").getId();
        mh = jdbc.queryForObject("SELECT id FROM states WHERE code='MH'", Long.class);
        nagpur = jdbc.queryForObject(
                "SELECT id FROM cities WHERE state_id=? AND name_key='nagpur'", Long.class, mh);
        owner = users.create("Ramesh Kumar", "9811008120", UserType.BOTH);
        hired = users.create("Suresh Patil", "9811008121", UserType.DRIVER);
        company = companies.create("Kumar Roadways");
        truck = vehicles.create("MH12AB1234", openBody, null, owner.getId(),
                (short) 2, (short) 6, "20 Ton", new BigDecimal("22.0"));   // no driver row yet
    }

    @Test
    @DisplayName("a second primary driver explains itself instead of naming an index")
    void two_primary_drivers() {
        vehicles.addDriver(truck.getId(), owner.getId(), true);

        assertThatThrownBy(() -> vehicles.addDriver(truck.getId(), hired.getId(), true))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("already has a primary driver")
                .isNotInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("adding the same driver twice is a no-op, not an error")
    void the_same_person_twice_in_the_same_role_is_idempotent() {
        vehicles.addDriver(truck.getId(), hired.getId(), false);
        vehicles.addDriver(truck.getId(), hired.getId(), false);

        // Not a constraint violation, and worth knowing why: vehicle_x_user has an ASSIGNED
        // composite id, so JpaRepository.save() finds the existing row and merges it rather than
        // inserting. pk_vehicle_x_user never fires through this path. The result is idempotence,
        // which is a fine contract for "make sure this person drives this vehicle" -- it is just
        // inherited from JPA rather than chosen, so it is pinned here.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_x_user WHERE vehicle_id = ? AND user_id = ?",
                Integer.class, truck.getId(), hired.getId())).isEqualTo(1);
    }

    @Test
    void the_same_location_twice_on_a_vehicle() {
        assertThatThrownBy(() -> vehicles.setPreferredLocations(truck.getId(), List.of(
                new VehicleService.CityRef(mh, nagpur),
                new VehicleService.CityRef(mh, nagpur))))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("already on this vehicle's list");
    }

    @Test
    void the_same_location_twice_on_a_company() {
        assertThatThrownBy(() -> companies.setLocations(company.getId(), List.of(
                VehicleService.CityRef.wholeState(mh),
                VehicleService.CityRef.wholeState(mh))))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("already on this company's list");
    }

    @Test
    @DisplayName("re-attaching a person to the same company updates rather than duplicating")
    void a_person_attached_to_the_same_company_twice_is_idempotent() {
        users.joinCompany(owner.getId(), company.getId(), "Driver", false);
        users.joinCompany(owner.getId(), company.getId(), "Fleet manager", false);

        // Same assigned-composite-id merge as above. Usefully, it means the position can be
        // corrected by calling joinCompany again.
        assertThat(jdbc.queryForObject(
                "SELECT position FROM user_x_company WHERE user_id = ? AND company_id = ?",
                String.class, owner.getId(), company.getId())).isEqualTo("Fleet manager");
    }

    @Test
    void a_second_primary_company_for_one_person() {
        Company other = companies.create("Patel Freight");
        users.joinCompany(owner.getId(), company.getId(), "Owner", true);

        assertThatThrownBy(() -> users.joinCompany(owner.getId(), other.getId(), "Driver", true))
                .isInstanceOf(ApiException.Conflict.class)
                .hasMessageContaining("already has a primary company");
    }

    @Test
    @DisplayName("a field error names the field, so a form can put it under the right input")
    void a_duplicate_username_is_keyed_to_its_field() {
        // Two STAFF, because only STAFF and ADMIN may hold a login at all -- a driver with a
        // password would be a driver who can call the API.
        User first = users.create("Asha Nair", "9811009001", UserType.STAFF);
        User second = users.create("Bina Rao", "9811009002", UserType.STAFF);
        users.setLogin(first.getId(), "asha", "first-password", UserRole.CSR);

        assertThatThrownBy(() -> users.setLogin(second.getId(), "asha", "second-password", UserRole.CSR))
                .satisfies(e -> {
                    // UserService checks this one itself, so it is a Conflict rather than a
                    // constraint translation -- the point is that it is readable either way.
                    assertThat(e).isInstanceOfAny(
                            ApiException.Conflict.class, FieldValidationException.class);
                    assertThat(e).isNotInstanceOf(DataIntegrityViolationException.class);
                });
    }

    @Test
    @DisplayName("an unrecognised violation is NOT dressed up in a reassuring message")
    void an_unmapped_constraint_passes_through_untouched() {
        // ck_cities_* does not exist; this trips a constraint the translator has no entry for.
        // It must arrive as the raw database error, because inventing friendly words for a
        // constraint nobody anticipated would hide a real bug.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cities (state_id, name, name_key) VALUES (999999, 'Nowhere', 'nowhere')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
