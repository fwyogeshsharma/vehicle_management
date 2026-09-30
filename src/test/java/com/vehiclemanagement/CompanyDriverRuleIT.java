package com.vehiclemanagement;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A company's truck may only be driven by someone on that company's books.
 *
 * <p>Two links exist between a company and a driver and they used to be independent: employment
 * in {@code user_x_company}, and "drives their truck" reachable only through
 * {@code vehicles.owner_company_id}. Nothing tied them, so a driver employed by nobody — or by a
 * rival — could be assigned to a company vehicle. 003 ties them with a foreign key.
 *
 * <p>The raw-SQL cases matter as much as the service ones: the rule has to hold against an
 * import or a psql session, not only against calls that come through Java.
 */
class CompanyDriverRuleIT extends DatabaseTest {

    @Autowired BodyTypeService bodyTypes;
    @Autowired UserService users;
    @Autowired CompanyService companies;
    @Autowired VehicleService vehicles;

    private Long openBody;
    private Company kumar;
    private Company rival;
    private User employed;
    private User outsider;
    private User ownerOperator;
    private Vehicle companyTruck;
    private Vehicle ownTruck;

    @BeforeEach
    void setUp() {
        resetDomainData();
        openBody = bodyTypes.ensure("Open body").getId();
        kumar = companies.create("Kumar Roadways");
        rival = companies.create("Patel Freight Lines");

        employed = users.create("Suresh Patil", "9811008121", UserType.DRIVER);
        users.joinCompany(employed.getId(), kumar.getId(), "Driver", true);

        outsider = users.create("Rajan Meena", "9811008127", UserType.DRIVER);
        users.joinCompany(outsider.getId(), rival.getId(), "Driver", true);

        ownerOperator = users.create("Ramesh Kumar", "9811008120", UserType.BOTH);

        companyTruck = vehicles.create("MH12AB1234", openBody, kumar.getId(), null,
                (short) 3, (short) 10, "32 Ton", new BigDecimal("32.0"));
        ownTruck = vehicles.create("MH14CD5678", openBody, null, ownerOperator.getId(),
                (short) 2, (short) 6, "16 Ton", new BigDecimal("22.0"), true);
    }

    @Nested
    @DisplayName("through the service")
    class ThroughTheService {

        @Test
        void a_company_driver_may_drive_the_company_truck() {
            assertThatCode(() -> vehicles.addDriver(companyTruck.getId(), employed.getId(), true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("someone on a rival's books may not, and is told why")
        void an_outsider_may_not() {
            assertThatThrownBy(() -> vehicles.addDriver(companyTruck.getId(), outsider.getId(), true))
                    .isInstanceOf(ApiException.Conflict.class)
                    .hasMessageContaining("Kumar Roadways")
                    .hasMessageContaining("Add this person to");
        }

        @Test
        void employing_them_first_makes_it_legal() {
            users.joinCompany(outsider.getId(), kumar.getId(), "Relief driver", false);
            assertThatCode(() -> vehicles.addDriver(companyTruck.getId(), outsider.getId(), false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an owner-operator may put anyone behind the wheel")
        void a_person_owned_vehicle_has_no_employment_rule() {
            // There is no company, so there is no payroll to be on. This is the single-truck
            // owner who hands the keys to a relief driver for a long haul.
            assertThatCode(() -> vehicles.addDriver(ownTruck.getId(), outsider.getId(), false))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("against raw SQL, which is the point of putting it in the schema")
    class AgainstRawSql {

        @Test
        void an_unemployed_driver_cannot_be_inserted_on_a_company_truck() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned)
                    VALUES (?,?,?,TRUE)""", companyTruck.getId(), outsider.getId(), kumar.getId()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("fk_vxu_employed");
        }

        @Test
        @DisplayName("nor by naming the company they DO work for")
        void the_company_named_must_be_the_vehicles_owner() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned)
                    VALUES (?,?,?,TRUE)""", companyTruck.getId(), outsider.getId(), rival.getId()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("fk_vxu_vehicle_company");
        }

        @Test
        @DisplayName("nor by claiming the truck is personally owned")
        void the_flag_cannot_be_used_to_dodge_the_rule() {
            // The obvious escape: say is_company_owned=false so the company checks skip. The
            // composite FK to vehicles has two NOT NULL columns, so it is never skipped.
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned)
                    VALUES (?,?,NULL,FALSE)""", companyTruck.getId(), outsider.getId()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("fk_vxu_vehicle_owned");
        }

        @Test
        void a_company_vehicle_row_must_name_its_company() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO vehicle_x_user (vehicle_id, user_id, owner_company_id, is_company_owned)
                    VALUES (?,?,NULL,TRUE)""", companyTruck.getId(), employed.getId()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_vxu_company_present");
        }
    }

    @Nested
    @DisplayName("when employment ends")
    class LeavingTheCompany {

        @Test
        @DisplayName("the driver stops driving that company's trucks, automatically")
        void removing_employment_removes_the_assignment() {
            vehicles.addDriver(companyTruck.getId(), employed.getId(), true);
            assertThat(driverCount()).isEqualTo(1);

            jdbc.update("DELETE FROM user_x_company WHERE user_id = ? AND company_id = ?",
                    employed.getId(), kumar.getId());

            // CASCADE rather than RESTRICT: leaving the company means leaving its trucks, and it
            // is the only way the rule stays true without someone remembering a second step.
            assertThat(driverCount())
                    .as("the assignment went with the employment")
                    .isZero();
        }

        private int driverCount() {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM vehicle_x_user WHERE vehicle_id = ?",
                    Integer.class, companyTruck.getId());
        }
    }
}
