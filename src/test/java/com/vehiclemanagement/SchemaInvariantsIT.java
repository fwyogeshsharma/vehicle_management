package com.vehiclemanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

import com.vehiclemanagement.service.Normalizer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules the database enforces on its own.
 *
 * <p>Every assertion here goes through raw SQL rather than the services, and that is the point:
 * these invariants exist so they hold against a native UPDATE, a data migration or psql, not only
 * against calls that come through Java. A test that went through VehicleService would prove the
 * service is careful, which is a much weaker claim.
 */
class SchemaInvariantsIT extends DatabaseTest {

    private long stateMh;
    private long stateGj;
    private long cityNagpur;
    private long citySurat;
    private long bodyType;
    private long ramesh;
    private long suresh;
    private long company;
    private long ownedByUser;
    private long ownedByCompany;

    @BeforeEach
    void setUp() {
        resetDomainData();
        stateMh = id("SELECT id FROM states WHERE code = 'MH'");
        stateGj = id("SELECT id FROM states WHERE code = 'GJ'");
        cityNagpur = id("SELECT id FROM cities WHERE state_id = " + stateMh + " AND name_key = 'nagpur'");
        citySurat = id("SELECT id FROM cities WHERE state_id = " + stateGj + " AND name_key = 'surat'");
        bodyType = id("SELECT id FROM body_types WHERE name_key = 'open body'");

        ramesh = insertUser("Ramesh Kumar", "9811008120", "BOTH");
        suresh = insertUser("Suresh Patil", "9811008121", "DRIVER");
        jdbc.update("INSERT INTO companies (name, name_key) VALUES ('Kumar Roadways', 'kumar roadways')");
        company = id("SELECT id FROM companies WHERE name_key = 'kumar roadways'");

        // 003: only a company's own drivers may ride its trucks, so employ them here
        jdbc.update("INSERT INTO user_x_company (user_id, company_id) VALUES (?,?), (?,?)",
                ramesh, company, suresh, company);

        ownedByUser = insertVehicle("MH12AB1234", null, ramesh);
        ownedByCompany = insertVehicle("MH14CD5678", company, null);
    }

    @Nested
    @DisplayName("a vehicle has exactly one owner")
    class Ownership {

        @Test
        void two_owners_are_refused() {
            assertThatThrownBy(() -> insertVehicle("MH01AA1111", company, ramesh))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_vehicles_one_owner");
        }

        @Test
        void no_owner_at_all_is_refused() {
            assertThatThrownBy(() -> insertVehicle("MH01AA2222", null, null))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_vehicles_one_owner");
        }

        @Test
        void an_owner_cannot_be_deleted_out_from_under_a_vehicle() {
            assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", ramesh))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("ownership lives on the vehicle row, and only there")
    class OwnershipIsNotInTheLinkTable {

        /**
         * vehicle_x_user used to carry an OWNER row mirroring vehicles.owner_user_id, policed by
         * a composite FK over a generated column. 002 removed all of it: the row said nothing the
         * vehicle row did not already say. These pin that it is really gone, so nobody
         * reintroduces the duplicate by adding a `role` column back.
         */
        @Test
        void the_link_table_has_no_role_column() {
            assertThat(columnsOf("vehicle_x_user"))
                    .doesNotContain("role", "owner_user_id")
                    .contains("vehicle_id", "user_id", "is_primary");
        }

        @Test
        void an_owner_operator_is_recorded_once_for_each_fact() {
            link(ownedByUser, ramesh);          // he drives it
            assertThat(id("SELECT owner_user_id FROM vehicles WHERE id = " + ownedByUser))
                    .as("the vehicle row says he owns it").isEqualTo(ramesh);
            assertThat(count("SELECT count(*) FROM vehicle_x_user WHERE vehicle_id = "
                             + ownedByUser)).as("one driver row, not two").isEqualTo(1);
        }

        @Test
        void a_company_vehicle_still_has_a_driver() {
            link(ownedByCompany, suresh);
            assertThat(count("SELECT count(*) FROM vehicle_x_user WHERE vehicle_id = "
                             + ownedByCompany)).isEqualTo(1);
        }

        @Test
        void at_most_one_primary_driver_per_vehicle() {
            primaryLink(ownedByCompany, suresh);
            assertThatThrownBy(() -> primaryLink(ownedByCompany, ramesh))
                    .hasMessageContaining("uq_vxu_primary_driver");
        }
    }

    @Nested
    @DisplayName("driver rows are held by the plain foreign keys, not the composite one")
    class DriverRowIntegrity {

        /**
         * The composite fk_vxu_owner_matches is skipped for DRIVER rows, because its generated
         * column is null there and MATCH SIMPLE skips a partially-null key. Without the plain
         * fk_vxu_vehicle and fk_vxu_user, a driver assignment could name rows that do not exist.
         * This is the test that caught their absence.
         */
        /**
         * Explicit column values rather than the {@code link} helper: that one derives the
         * mirror columns with a SELECT over `vehicles`, which for a missing vehicle simply
         * inserts nothing and proves nothing.
         */
        @Test
        void a_driver_row_for_a_vehicle_that_does_not_exist_is_refused() {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id,"
                    + " is_company_owned) VALUES (?,?,NULL,FALSE)", 999_999L, suresh))
                    .hasMessageContaining("fk_vxu_vehicle");
        }

        @Test
        void a_driver_row_for_a_person_who_does_not_exist_is_refused() {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id,"
                    + " is_company_owned) VALUES (?,?,NULL,FALSE)", ownedByUser, 999_999L))
                    .hasMessageContaining("fk_vxu_user");
        }
    }

    @Nested
    @DisplayName("preferred locations")
    class Locations {

        @Test
        void a_whole_state_is_a_row_with_no_city() {
            assertThatCode(() -> vehicleLocation(ownedByUser, stateMh, null)).doesNotThrowAnyException();
        }

        @Test
        void the_same_whole_state_twice_is_refused() {
            vehicleLocation(ownedByUser, stateMh, null);
            assertThatThrownBy(() -> vehicleLocation(ownedByUser, stateMh, null))
                    .hasMessageContaining("uq_vxl");
        }

        @Test
        void a_city_in_the_wrong_state_is_refused() {
            assertThatThrownBy(() -> vehicleLocation(ownedByUser, stateMh, citySurat))
                    .hasMessageContaining("fk_vxl_city_in_state");
        }

        @Test
        @DisplayName("a company-owned vehicle MAY have its own, and they add to the company's")
        void a_company_owned_vehicle_may_have_its_own() {
            // Reversed by changeset 009. The schema used to forbid this outright -- a CHECK, a
            // discriminator column and a composite FK -- which made a fleet covering one state
            // with a single truck on a city shuttle impossible to describe.
            vehicleLocation(ownedByCompany, stateMh, cityNagpur);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM vehicle_x_location WHERE vehicle_id = ?",
                    Integer.class, ownedByCompany)).isEqualTo(1);

            // Additive: the view reports the company's row AND the vehicle's, tagged.
            assertThat(jdbc.queryForList(
                    "SELECT source FROM vehicle_effective_locations WHERE vehicle_id = ?",
                    String.class, ownedByCompany))
                    .contains("VEHICLE");
        }

        @Test
        @DisplayName("selling to a company is no longer blocked by the vehicle's own locations")
        void selling_to_a_company_is_allowed_while_the_vehicle_has_its_own() {
            // The old composite FK carried ON UPDATE NO ACTION precisely to reject this. With the
            // rule gone the sale succeeds; VehicleService still clears the rows on the way
            // through, for provenance rather than exclusivity -- see OwnershipJourneyIT.
            vehicleLocation(ownedByUser, stateMh, cityNagpur);

            jdbc.update("UPDATE vehicles SET owner_user_id = NULL, owner_company_id = ? WHERE id = ?",
                    company, ownedByUser);

            assertThat(jdbc.queryForObject(
                    "SELECT owner_company_id FROM vehicles WHERE id = ?", Long.class, ownedByUser))
                    .isEqualTo(company);
        }
    }

    @Nested
    @DisplayName("columns the database maintains")
    class DerivedColumns {

        @Test
        void capacity_tons_follows_the_free_text() {
            jdbc.update("UPDATE vehicles SET capacity = '22.5 MT' WHERE id = ?", ownedByCompany);
            BigDecimal tons = jdbc.queryForObject(
                    "SELECT capacity_tons FROM vehicles WHERE id = ?", BigDecimal.class, ownedByCompany);
            // Written as '[0-9]+(\.[0-9]+)?' this silently yields 0.5 -- substring() returns the
            // first PARENTHESISED subexpression, not the whole match.
            assertThat(tons).isEqualByComparingTo("22.5");
        }

        @Test
        void an_unparseable_capacity_is_null_rather_than_an_error() {
            jdbc.update("UPDATE vehicles SET capacity = 'no idea' WHERE id = ?", ownedByCompany);
            assertThat(jdbc.queryForObject("SELECT capacity_tons FROM vehicles WHERE id = ?",
                    BigDecimal.class, ownedByCompany)).isNull();
        }

        /**
         * The capacity rule is written twice — a Java regex and a SQL generated column — because
         * the service needs to range-check a value before the insert that the database will
         * derive anyway. Two copies of one rule drift. This is the test that stops it.
         *
         * <p>The inputs are chosen to hit the ways the two engines could disagree: a decimal, a
         * number with no space before the unit, a number that is not first in the string, two
         * numbers, punctuation inside the number, and nothing parseable at all.
         */
        @ParameterizedTest
        @ValueSource(strings = {"20 Ton", "22.5 MT", "50T", "9 tonnes", "0.5 T", "  16  ",
                                "Ton 20", "20-25 Ton", "20.5.5 T", "MT20", "20,000 kg",
                                "1e5", "no idea", "heavy"})
        // "" is deliberately absent: ck_vehicles_capacity refuses a blank capacity outright, so
        // there is no stored value for the two rules to agree or disagree about.
        void the_java_and_sql_capacity_rules_agree(String capacity) {
            jdbc.update("UPDATE vehicles SET capacity = ? WHERE id = ?", capacity, ownedByCompany);
            BigDecimal fromDatabase = jdbc.queryForObject(
                    "SELECT capacity_tons FROM vehicles WHERE id = ?",
                    BigDecimal.class, ownedByCompany);
            BigDecimal fromJava = Normalizer.capacityTons(capacity);

            if (fromJava == null || fromDatabase == null) {
                assertThat(fromDatabase).as("capacity %s", capacity).isNull();
                assertThat(fromJava).as("capacity %s", capacity).isNull();
            } else {
                assertThat(fromDatabase).as("capacity %s", capacity)
                        .isEqualByComparingTo(fromJava);
            }
        }

        @Test
        void updated_at_moves_for_a_native_update_not_only_an_orm_flush() {
            jdbc.update("UPDATE vehicles SET notes = 'touched' WHERE id = ?", ownedByCompany);
            assertThat(jdbc.queryForObject(
                    "SELECT updated_at > created_at FROM vehicles WHERE id = ?",
                    Boolean.class, ownedByCompany)).isTrue();
        }
    }

    @Nested
    @DisplayName("logins")
    class Logins {

        @Test
        void any_number_of_people_may_have_no_login() {
            insertUser("Anita Devi", "9811008122", "DRIVER");
            assertThatCode(() -> insertUser("Balwinder Sidhu", "9811008124", "DRIVER"))
                    .doesNotThrowAnyException();
        }

        @Test
        void a_username_with_no_password_is_refused() {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO users (name, mobile, user_type, username) VALUES (?,?,?,?)",
                    "Half Login", "9811008125", "STAFF", "half"))
                    .hasMessageContaining("ck_users_login_pair");
        }

        @Test
        void a_number_that_is_not_an_indian_mobile_is_refused() {
            assertThatThrownBy(() -> insertUser("Bad Number", "1234567890", "DRIVER"))
                    .hasMessageContaining("ck_users_mobile");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long insertUser(String name, String mobile, String type) {
        jdbc.update("INSERT INTO users (name, mobile, user_type) VALUES (?,?,?)", name, mobile, type);
        return id("SELECT id FROM users WHERE mobile = '" + mobile + "'");
    }

    private long insertVehicle(String reg, Long companyId, Long userId) {
        jdbc.update("INSERT INTO vehicles (registration_number, body_type_id, owner_company_id, owner_user_id)"
                + " VALUES (?,?,?,?)", reg, bodyType, companyId, userId);
        return id("SELECT id FROM vehicles WHERE registration_number = '" + reg + "'");
    }

    /**
     * Insert a driver row the way the service does: the owning company is read off the vehicle,
     * never chosen. Since 003, a row that does not mirror the vehicle is refused.
     */
    private void link(long vehicleId, long userId) {
        jdbc.update("""
                INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned)
                SELECT ?, ?, v.owner_company_id, v.is_company_owned
                  FROM vehicles v WHERE v.id = ?""", vehicleId, userId, vehicleId);
    }

    private void primaryLink(long vehicleId, long userId) {
        jdbc.update("""
            INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned, is_primary)
            SELECT ?, ?, v.owner_company_id, v.is_company_owned, TRUE
              FROM vehicles v WHERE v.id = ?""", vehicleId, userId, vehicleId);
    }

    private java.util.List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }

    private void vehicleLocation(long vehicleId, Long stateId, Long cityId) {
        jdbc.update("INSERT INTO vehicle_x_location (vehicle_id, state_id, city_id) VALUES (?,?,?)",
                vehicleId, stateId, cityId);
    }
}
