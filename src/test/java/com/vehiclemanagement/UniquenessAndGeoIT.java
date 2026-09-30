package com.vehiclemanagement;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.VehicleUserRepository;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.service.GeoService;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The remaining guarantees: what may not be duplicated, what may not be deleted, and the
 * geography rules — including the one that has to refuse to guess.
 */
class UniquenessAndGeoIT extends DatabaseTest {

    @Autowired GeoService geo;
    @Autowired BodyTypeService bodyTypes;
    @Autowired UserService userService;
    @Autowired CompanyService companyService;
    @Autowired VehicleService vehicleService;
    @Autowired VehicleUserRepository vehicleUsers;

    private Long openBody;

    @BeforeEach
    void setUp() {
        resetDomainData();
        openBody = bodyTypes.ensure("Open body").getId();
    }

    @Nested
    @DisplayName("nothing important can be duplicated")
    class Uniqueness {

        @Test
        void one_person_per_mobile_number() {
            userService.create("Ramesh Kumar", "9811008120", UserType.DRIVER);
            assertThatThrownBy(() -> userService.create("Someone Else", "+91 98110 08120",
                    UserType.OWNER))
                    .isInstanceOf(ApiException.Conflict.class)
                    .hasMessageContaining("9811008120");
        }

        @Test
        void one_vehicle_per_registration_however_it_is_spelled() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            vehicleService.create("MH12AB1234", openBody, null, owner.getId(),
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            assertThatThrownBy(() -> vehicleService.create("mh 12-ab 1234", openBody,
                    null, owner.getId(), (short) 2, (short) 6, "20 Ton", BigDecimal.TEN))
                    .isInstanceOf(ApiException.Conflict.class);
        }

        @Test
        void one_company_per_name() {
            companyService.create("Kumar Roadways");
            assertThatThrownBy(() -> companyService.create("  kumar   roadways "))
                    .isInstanceOf(ApiException.Conflict.class);
        }

        @Test
        void one_company_per_gstin_but_any_number_without_one() {
            Company a = companyService.create("Kumar Roadways");
            Company b = companyService.create("Patel Freight");
            jdbc.update("UPDATE companies SET gstin = '27AAPFU0939F1ZV' WHERE id = ?", a.getId());
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE companies SET gstin = '27AAPFU0939F1ZV' WHERE id = ?", b.getId()))
                    .isInstanceOf(DataAccessException.class);
            // but two companies with no GSTIN at all are fine — NULLs stay distinct
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM companies WHERE gstin IS NULL", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        void a_malformed_gstin_is_refused_by_the_database() {
            Company c = companyService.create("Kumar Roadways");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE companies SET gstin = 'NOPE' WHERE id = ?", c.getId()))
                    .hasMessageContaining("ck_companies_gstin");
        }
    }

    @Nested
    @DisplayName("what may not be deleted, and what goes when it is")
    class Deletion {

        @Test
        void a_company_that_still_owns_a_vehicle_cannot_be_deleted() {
            Company c = companyService.create("Kumar Roadways");
            vehicleService.create("MH12AB1234", openBody, c.getId(), null,
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            // RESTRICT, not SET NULL: a fleet does not become ownerless because someone tidied
            // the directory. SET NULL would leave num_nonnulls(...) = 0 anyway.
            assertThatThrownBy(() -> jdbc.update("DELETE FROM companies WHERE id = ?", c.getId()))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        void a_body_type_in_use_cannot_be_deleted_but_can_be_retired() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            vehicleService.create("MH12AB1234", openBody, null, owner.getId(),
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM body_types WHERE id = ?", openBody))
                    .isInstanceOf(DataAccessException.class);

            bodyTypes.retire(openBody);
            assertThatThrownBy(() -> vehicleService.create("MH99ZZ9999", openBody, null,
                    owner.getId(), (short) 2, (short) 6, "20 Ton", BigDecimal.TEN))
                    .isInstanceOf(FieldValidationException.class)
                    .hasMessageContaining("retired");
        }

        @Test
        void deleting_a_vehicle_takes_its_links_with_it() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            Vehicle v = vehicleService.create("MH12AB1234", openBody, null, owner.getId(),
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            Long mh = jdbc.queryForObject("SELECT id FROM states WHERE code='MH'", Long.class);
            vehicleService.setPreferredLocations(v.getId(),
                    List.of(VehicleService.CityRef.wholeState(mh)));

            jdbc.update("DELETE FROM vehicles WHERE id = ?", v.getId());

            assertThat(vehicleUsers.findByVehicleId(v.getId())).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM vehicle_x_location WHERE vehicle_id = ?",
                    Integer.class, v.getId())).isZero();
        }
    }

    @Nested
    @DisplayName("the vehicle_x_company view")
    class ReadOnlyView {

        @Test
        void it_reports_the_company_owner() {
            Company c = companyService.create("Kumar Roadways");
            Vehicle v = vehicleService.create("MH12AB1234", openBody, c.getId(), null,
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            assertThat(jdbc.queryForObject(
                    "SELECT company_id FROM vehicle_x_company WHERE vehicle_id = ?",
                    Long.class, v.getId())).isEqualTo(c.getId());
        }

        @Test
        void an_independent_vehicle_simply_is_not_in_it() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            Vehicle v = vehicleService.create("MH12AB1234", openBody, null, owner.getId(),
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM vehicle_x_company WHERE vehicle_id = ?",
                    Integer.class, v.getId())).isZero();
        }

        @Test
        void writing_to_it_fails_rather_than_silently_doing_nothing() {
            Company c = companyService.create("Kumar Roadways");
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            Vehicle v = vehicleService.create("MH12AB1234", openBody, null, owner.getId(),
                    (short) 2, (short) 6, "20 Ton", BigDecimal.TEN);
            // Anyone taking the name at face value gets an error, not a no-op. Ownership is set
            // by updating vehicles.
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO vehicle_x_company (vehicle_id, company_id) VALUES (?,?)",
                    v.getId(), c.getId()))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("geography refuses to guess")
    class Geography {

        @Test
        void the_same_city_name_in_two_states_is_legitimate() {
            List<City> hyderabads = jdbc.query(
                    "SELECT id FROM cities WHERE name_key = 'hyderabad'",
                    (rs, n) -> null);
            assertThat(hyderabads.size())
                    .as("Telangana and Sindh-era Andhra both have one; the seed file has both")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        void an_ambiguous_name_is_an_error_rather_than_a_coin_flip() {
            Integer ambiguous = jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT name_key FROM cities GROUP BY name_key HAVING count(*) > 1
                    ) d""", Integer.class);
            assertThat(ambiguous).as("the seed data must contain at least one, or this proves nothing")
                    .isPositive();

            String duplicated = jdbc.queryForObject("""
                    SELECT name_key FROM cities GROUP BY name_key HAVING count(*) > 1 LIMIT 1
                    """, String.class);
            assertThatThrownBy(() -> geo.resolveCityByName(duplicated, "from_place"))
                    .isInstanceOf(FieldValidationException.class)
                    .hasMessageContaining("more than one state");
        }

        @Test
        void an_unknown_place_says_so() {
            assertThatThrownBy(() -> geo.resolveCityByName("Nowhereville", "from_place"))
                    .isInstanceOf(FieldValidationException.class)
                    .hasMessageContaining("not in the city list");
        }

        @Test
        void an_unambiguous_name_resolves() {
            City nagpur = geo.resolveCityByName("nagpur", "from_place");
            assertThat(nagpur.getName()).isEqualTo("Nagpur");
        }
    }

    @Nested
    @DisplayName("physically impossible vehicles are refused by the database itself")
    class PhysicalBounds {

        @Test
        void a_forty_axle_vehicle_is_refused() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicles (registration_number, body_type_id, owner_user_id, no_of_axles)
                    VALUES ('MH40AX0001', ?, ?, 40)""", openBody, owner.getId()))
                    .hasMessageContaining("ck_vehicles_axles");
        }

        @Test
        void an_odd_number_of_wheels_is_refused() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicles (registration_number, body_type_id, owner_user_id, no_of_wheels)
                    VALUES ('MH07WH0001', ?, ?, 7)""", openBody, owner.getId()))
                    .hasMessageContaining("ck_vehicles_wheels");
        }

        @Test
        void a_non_canonical_registration_cannot_be_smuggled_in_by_raw_sql() {
            User owner = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
            // The service normalises; this is the guard for everything that does not go through it.
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicles (registration_number, body_type_id, owner_user_id)
                    VALUES ('mh 12 ab', ?, ?)""", openBody, owner.getId()))
                    .hasMessageContaining("ck_vehicles_reg_canon");
        }
    }
}
