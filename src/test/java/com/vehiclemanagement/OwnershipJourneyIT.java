package com.vehiclemanagement;

import com.vehiclemanagement.domain.*;
import com.vehiclemanagement.repo.VehicleRepository;
import com.vehiclemanagement.repo.VehicleUserRepository;
import com.vehiclemanagement.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A truck's life through the services: owned by its driver, sold to a company, sold back.
 *
 * <p>This is where the two halves meet — the statement ordering VehicleService has to get right,
 * and the location fallback the view defines. If this passes, the model works end to end.
 */
class OwnershipJourneyIT extends DatabaseTest {

    @Autowired GeoService geo;
    @Autowired BodyTypeService bodyTypes;
    @Autowired UserService userService;
    @Autowired CompanyService companyService;
    @Autowired VehicleService vehicleService;
    @Autowired VehicleRepository vehicles;
    @Autowired VehicleUserRepository vehicleUsers;

    private Long mh;
    private Long gj;
    private Long nagpur;
    private Long pune;
    private Long surat;
    private Long openBody;

    private User driver;
    private Company company;
    private Vehicle truck;

    @BeforeEach
    void setUp() {
        resetDomainData();
        mh = jdbc.queryForObject("SELECT id FROM states WHERE code='MH'", Long.class);
        gj = jdbc.queryForObject("SELECT id FROM states WHERE code='GJ'", Long.class);
        nagpur = jdbc.queryForObject(
                "SELECT id FROM cities WHERE state_id=? AND name_key='nagpur'", Long.class, mh);
        pune = jdbc.queryForObject(
                "SELECT id FROM cities WHERE state_id=? AND name_key='pune'", Long.class, mh);
        surat = jdbc.queryForObject(
                "SELECT id FROM cities WHERE state_id=? AND name_key='surat'", Long.class, gj);
        openBody = bodyTypes.ensure("Open body").getId();

        driver = userService.create("Ramesh Kumar", "+91 98110 08120", UserType.BOTH);
        company = companyService.create("Kumar Roadways");
        // the company runs anywhere in Maharashtra, plus Surat specifically
        companyService.setLocations(company.getId(), List.of(
                VehicleService.CityRef.wholeState(mh),
                new VehicleService.CityRef(gj, surat)));

        // the owner-operator: owns it and drives it, in one call
        truck = vehicleService.create("mh 12-ab 1234", openBody, null, driver.getId(),
                (short) 2, (short) 6, "20 Ton", new BigDecimal("22.0"), true);
    }

    @Test
    void the_registration_is_stored_canonically_however_it_was_typed() {
        assertThat(truck.getRegistrationNumber()).isEqualTo("MH12AB1234");
    }

    @Test
    void an_owner_operator_is_recorded_as_owner_on_the_vehicle_and_as_a_driver() {
        assertThat(truck.getOwnerUserId())
                .as("ownership is the vehicle row").isEqualTo(driver.getId());
        assertThat(vehicleUsers.findByVehicleId(truck.getId()))
                .as("driving is the link table")
                .extracting(VehicleUser::getUserId).containsExactly(driver.getId());
    }

    @Test
    void an_independent_vehicle_serves_its_own_locations() {
        vehicleService.setPreferredLocations(truck.getId(),
                List.of(new VehicleService.CityRef(mh, nagpur)));

        assertThat(vehicleService.effectiveLocations(truck.getId()))
                .extracting(VehicleRepository.EffectiveLocation::getCityName,
                            VehicleRepository.EffectiveLocation::getSource)
                .containsExactly(tuple("Nagpur", "VEHICLE"));

        assertThat(vehicleService.servingCity(nagpur)).extracting(Vehicle::getId)
                .contains(truck.getId());
        assertThat(vehicleService.servingCity(pune)).extracting(Vehicle::getId)
                .doesNotContain(truck.getId());
    }

    @Test
    void selling_to_a_company_switches_the_locations_and_clears_the_old_ones() {
        vehicleService.setPreferredLocations(truck.getId(),
                List.of(new VehicleService.CityRef(mh, nagpur)));

        vehicleService.setOwner(truck.getId(), company.getId(), null);

        // the vehicle's own rows are gone, not dormant
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_x_location WHERE vehicle_id = ?",
                Integer.class, truck.getId())).isZero();

        // and it now runs where the company runs -- including a whole state
        assertThat(vehicleService.effectiveLocations(truck.getId()))
                .extracting(VehicleRepository.EffectiveLocation::getSource)
                .containsOnly("COMPANY");
        assertThat(vehicleService.servingCity(pune)).extracting(Vehicle::getId)
                .contains(truck.getId());
        assertThat(vehicleService.servingCity(surat)).extracting(Vehicle::getId)
                .contains(truck.getId());
    }

    @Test
    void a_whole_state_preference_matches_every_city_in_it() {
        vehicleService.setOwner(truck.getId(), company.getId(), null);
        // Maharashtra is a state-only row on the company
        assertThat(vehicleService.servingCity(nagpur)).extracting(Vehicle::getId)
                .contains(truck.getId());
        assertThat(vehicleService.servingCity(pune)).extracting(Vehicle::getId)
                .contains(truck.getId());
    }

    @Test
    void selling_back_leaves_it_serving_nowhere_rather_than_resurrecting_the_old_list() {
        vehicleService.setPreferredLocations(truck.getId(),
                List.of(new VehicleService.CityRef(mh, nagpur)));
        vehicleService.setOwner(truck.getId(), company.getId(), null);
        vehicleService.setOwner(truck.getId(), null, driver.getId());

        assertThat(vehicleService.effectiveLocations(truck.getId())).isEmpty();
        assertThat(vehicleUsers.findByVehicleId(truck.getId()))
                .as("drivers went with the outgoing owner; setOwner was not told he drives")
                .isEmpty();
        assertThat(vehicleService.get(truck.getId()).getOwnerUserId()).isEqualTo(driver.getId());
    }

    @Test
    void drivers_go_with_the_outgoing_owner() {
        User hired = userService.create("Suresh Patil", "9811008121", UserType.DRIVER);
        // 003: a company's truck takes only a company's driver, so hire him first
        userService.joinCompany(hired.getId(), company.getId(), "Driver", true);
        vehicleService.setOwner(truck.getId(), company.getId(), null);
        vehicleService.addDriver(truck.getId(), hired.getId(), true);

        vehicleService.setOwner(truck.getId(), null, driver.getId(), true);

        // the previous owner's driver is not still answering "who drives this?"
        assertThat(vehicleUsers.findByVehicleId(truck.getId()))
                .extracting(VehicleUser::getUserId).containsExactly(driver.getId());
    }

    @Test
    void a_company_owned_vehicle_can_be_given_locations_of_its_own() {
        // Reversed by changeset 009: a truck's own route now sits on top of its company's rather
        // than being forbidden by it. A fleet covering Maharashtra can say that this one runs the
        // Nagpur shuttle without rerouting every other truck the company owns.
        vehicleService.setOwner(truck.getId(), company.getId(), null);

        vehicleService.setPreferredLocations(truck.getId(),
                List.of(new VehicleService.CityRef(mh, nagpur)));

        assertThat(vehicleService.effectiveLocations(truck.getId()))
                .extracting(VehicleRepository.EffectiveLocation::getSource)
                .contains("VEHICLE", "COMPANY");
    }

    @Test
    void a_company_with_no_locations_serves_nowhere_rather_than_falling_back() {
        companyService.setLocations(company.getId(), List.of());
        vehicleService.setOwner(truck.getId(), company.getId(), null);
        assertThat(vehicleService.effectiveLocations(truck.getId())).isEmpty();
    }

    @Test
    void the_two_directions_of_the_rule_agree() {
        vehicleService.setPreferredLocations(truck.getId(),
                List.of(new VehicleService.CityRef(mh, nagpur)));
        // whatever effectiveLocations says this truck serves, servingCity must agree
        for (VehicleRepository.EffectiveLocation l : vehicleService.effectiveLocations(truck.getId())) {
            if (l.getCityId() != null) {
                assertThat(vehicleService.servingCity(l.getCityId()))
                        .extracting(Vehicle::getId).contains(truck.getId());
            }
        }
    }

    @Test
    void a_vehicle_cannot_be_created_with_two_owners_or_none() {
        assertThatThrownBy(() -> vehicleService.create("MH99ZZ9999", openBody,
                company.getId(), driver.getId(), (short) 2, (short) 6, "20 Ton", BigDecimal.TEN))
                .hasMessageContaining("exactly one owner");
        assertThatThrownBy(() -> vehicleService.create("MH99ZZ8888", openBody,
                null, null, (short) 2, (short) 6, "20 Ton", BigDecimal.TEN))
                .hasMessageContaining("exactly one owner");
    }

    @Test
    void a_company_cannot_be_recorded_as_driving_its_own_vehicle() {
        assertThatThrownBy(() -> vehicleService.create("MH99ZZ6666", openBody,
                company.getId(), null, (short) 2, (short) 6, "20 Ton", BigDecimal.TEN, true))
                .hasMessageContaining("cannot drive its own vehicle");
    }

    @Test
    void an_implausible_wheel_count_is_refused_with_a_readable_message() {
        assertThatThrownBy(() -> vehicleService.create("MH99ZZ7777", openBody,
                null, driver.getId(), (short) 2, (short) 20, "20 Ton", BigDecimal.TEN))
                .hasMessageContaining("2-axle vehicle has between 4 and 8 wheels");
    }
}
