package com.vehiclemanagement.bootstrap;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.StateRepository;
import com.vehiclemanagement.repo.VehicleRepository;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Believable sample data to click around in. Development only.
 *
 * <p>Off unless {@code VM_SEED_SAMPLE=true}, and it <b>refuses a database that already has
 * vehicles</b> — seeding on start-up is a foot-gun the moment someone points a fresh checkout at
 * something that is not a scratch database.
 *
 * <p>Everything goes in through the services rather than raw SQL, so it exercises the same
 * validation the callers do. If a rule changes and this starts failing, that is the seeder doing
 * its job.
 *
 * <p>It deliberately covers <b>every branch of the model</b>, because otherwise the only place
 * these shapes exist is the test suite:
 *
 * <ul>
 *   <li>a company with several locations, including a whole state, and vehicles of its own
 *   <li>an owner-operator: owns his truck (the vehicle row) and drives it (a driver row)
 *   <li>a driver with <b>no login at all</b> — null username and password hash
 *   <li>one person on two companies' books
 *   <li>a company vehicle with a hired driver who does not own it — and who had to be put on
 *       the company's books first, because only its own drivers may ride its trucks
 *   <li>a company with <b>no</b> locations, whose vehicle therefore serves nowhere
 * </ul>
 */
@Component
@Order(10)
public class SampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    /**
     * The password for the one sample account, printed at start-up.
     *
     * <p>Fixed and in the source on purpose: this seeder only ever runs with
     * {@code VM_SEED_SAMPLE=true} against a scratch database, and a random one nobody can read
     * would make the account useless. It is not a default for anything real — the bootstrap
     * administrator takes its password from the environment and has no fallback.
     */
    private static final String SAMPLE_PASSWORD = "sample-password";

    private final com.vehiclemanagement.config.VehicleManagementProperties properties;
    private final GeoLookup geo;
    private final BodyTypeService bodyTypes;
    private final UserService users;
    private final CompanyService companies;
    private final VehicleService vehicles;
    private final VehicleRepository vehicleRepo;

    public SampleDataSeeder(com.vehiclemanagement.config.VehicleManagementProperties properties,
                            StateRepository states, CityRepository cities,
                            BodyTypeService bodyTypes, UserService users,
                            CompanyService companies, VehicleService vehicles,
                            VehicleRepository vehicleRepo) {
        this.properties = properties;
        this.geo = new GeoLookup(states, cities);
        this.bodyTypes = bodyTypes;
        this.users = users;
        this.companies = companies;
        this.vehicles = vehicles;
        this.vehicleRepo = vehicleRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isSeedSampleData()) {
            return;
        }
        if (vehicleRepo.count() > 0) {
            log.warn("Sample data skipped: this database already has {} vehicle(s). "
                     + "Refusing to add Ramesh Kumar to something that might be real.",
                    vehicleRepo.count());
            return;
        }

        Long mh = geo.state("MH");
        Long gj = geo.state("GJ");
        Long pb = geo.state("PB");
        Long nagpur = geo.city(mh, "nagpur");
        Long pune = geo.city(mh, "pune");
        Long surat = geo.city(gj, "surat");
        Long openBody = bodyTypes.ensure("Open body").getId();
        Long container = bodyTypes.ensure("Container 32 ft").getId();
        Long trailer = bodyTypes.ensure("Trailer").getId();

        // ── a company that runs across a whole state, plus one city elsewhere ──────
        Company kumar = companies.create("Kumar Roadways");
        companies.setLocations(kumar.getId(), List.of(
                VehicleService.CityRef.wholeState(mh),
                new VehicleService.CityRef(gj, surat)));

        User fleetManager = users.create("Anita Devi", "9811008130", UserType.STAFF);
        // A real, usable login -- the password is printed below so a developer can actually sign
        // in. It was a placeholder string until the API increment, which meant the one seeded
        // account could never authenticate and nobody noticed until login existed.
        users.setLogin(fleetManager.getId(), "anita", SAMPLE_PASSWORD, UserRole.CSR);
        users.joinCompany(fleetManager.getId(), kumar.getId(), "Fleet manager", true);

        Vehicle companyTruck = vehicles.create("MH12AB1234", container,
                kumar.getId(), null, (short) 3, (short) 10, "32 Ton", new BigDecimal("32.0"));

        // a hired driver: drives it, does not own it, and never signs in. He must be on the
        // company's books BEFORE he can be put on its truck -- that is the rule 003 enforces.
        User hired = users.create("Suresh Patil", "9811008121", UserType.DRIVER);
        users.joinCompany(hired.getId(), kumar.getId(), "Driver", true);
        vehicles.addDriver(companyTruck.getId(), hired.getId(), true);

        // ── an owner-driver: owns his truck and drives it, and picks his own routes ──
        User owner = users.create("Ramesh Kumar", "9811008120", UserType.BOTH);
        // one call, because the owner-operator is the commonest shape this system serves
        Vehicle ownTruck = vehicles.create("MH14CD5678", openBody,
                null, owner.getId(), (short) 2, (short) 6, "16 Ton", new BigDecimal("22.0"),
                true);
        vehicles.setPreferredLocations(ownTruck.getId(), List.of(
                new VehicleService.CityRef(mh, nagpur),
                new VehicleService.CityRef(mh, pune),
                VehicleService.CityRef.wholeState(pb)));

        // ── one person on two companies' books ────────────────────────────────────
        Company patel = companies.create("Patel Freight Lines");
        companies.setLocations(patel.getId(), List.of(
                VehicleService.CityRef.wholeState(gj)));
        users.joinCompany(hired.getId(), patel.getId(), "Driver", false);

        // ── a company with NO locations: its vehicle serves nowhere, on purpose ────
        Company quiet = companies.create("Deccan Movers");
        vehicles.create("KA05MN3322", trailer, quiet.getId(), null,
                (short) 4, (short) 14, "40 Ton", new BigDecimal("40.0"));

        log.info("Sample data loaded: {} vehicles, {} companies. "
                 + "Note Deccan Movers has no locations, so its vehicle serves nowhere -- "
                 + "that is the documented behaviour, not a bug.",
                vehicleRepo.count(), 3);
        log.info("Sample login: username 'anita', password '{}' (STAFF, not an administrator).",
                SAMPLE_PASSWORD);
    }

    /** Small helper so the seeder reads as a story rather than as repository plumbing. */
    private record GeoLookup(StateRepository states, CityRepository cities) {

        Long state(String code) {
            return states.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException(
                            "State " + code + " missing -- run with reference-data seeding on."))
                    .getId();
        }

        Long city(Long stateId, String nameKey) {
            return cities.findByStateIdAndNameKey(stateId, nameKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "City " + nameKey + " missing from state " + stateId))
                    .getId();
        }
    }
}
