package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.*;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.*;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vehicles: creating them, moving them between owners, and saying where they run.
 *
 * <p>Most of the rules this class appears to enforce are actually enforced by the schema — one
 * owner and only one, an OWNER row that matches the real owner, no preferred locations on a
 * company-owned vehicle. That is deliberate: they hold against a native UPDATE, a data
 * migration and psql, not only against calls that come through here. What this class adds is
 * (a) the statement ORDER those constraints require, and (b) turning a constraint violation
 * into a message a person can act on.
 */
@Service
public class VehicleService {

    private static final BigDecimal MIN_TONS = new BigDecimal("0.1");
    private static final BigDecimal MAX_TONS = new BigDecimal("200");

    private final VehicleRepository vehicles;
    private final VehicleUserRepository vehicleUsers;
    private final VehicleLocationRepository vehicleLocations;
    private final CompanyRepository companies;
    private final UserRepository users;
    private final BodyTypeRepository bodyTypes;
    private final CityRepository cities;
    private final UserCompanyRepository memberships;
    private final CompanyLocationRepository companyLocationRepo;
    private final EntityManager em;

    public VehicleService(VehicleRepository vehicles,
                          VehicleUserRepository vehicleUsers,
                          VehicleLocationRepository vehicleLocations,
                          CompanyRepository companies,
                          UserRepository users,
                          BodyTypeRepository bodyTypes,
                          CityRepository cities,
                          UserCompanyRepository memberships,
                          CompanyLocationRepository companyLocationRepo,
                          EntityManager em) {
        this.vehicles = vehicles;
        this.vehicleUsers = vehicleUsers;
        this.vehicleLocations = vehicleLocations;
        this.companies = companies;
        this.users = users;
        this.bodyTypes = bodyTypes;
        this.cities = cities;
        this.memberships = memberships;
        this.companyLocationRepo = companyLocationRepo;
        this.em = em;
    }

    public Vehicle get(long vehicleId) {
        return vehicles.findById(vehicleId).orElseThrow(
                () -> new ApiException.NotFound("Vehicle " + vehicleId + " not found."));
    }

    /**
     * Register a vehicle, owned either by a company or by a person.
     *
     * <p>Exactly one of {@code ownerCompanyId} / {@code ownerUserId} must be given. The database
     * would refuse the other cases anyway; checking here buys a message that names the field.
     */
    @Transactional
    public Vehicle create(String registrationNumber, Long bodyTypeId,
                          Long ownerCompanyId, Long ownerUserId,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt) {
        return create(registrationNumber, bodyTypeId, ownerCompanyId, ownerUserId,
                axles, wheels, capacity, lengthFt, false);
    }

    /**
     * Register a vehicle, owned either by a company or by a person.
     *
     * <p>{@code ownerAlsoDrives} records the owner as a driver too. That is the owner-operator —
     * a driver who owns his own truck, and most of Indian trucking. It is an explicit flag rather
     * than inferred from {@link UserType}, because an owner who employs a driver and never sits
     * in the cab is just as real, and guessing would be wrong for them half the time.
     *
     * <p>Exactly one of {@code ownerCompanyId} / {@code ownerUserId} must be given. The database
     * would refuse the other cases anyway; checking here buys a message that names the field.
     */
    @Transactional
    public Vehicle create(String registrationNumber, Long bodyTypeId,
                          Long ownerCompanyId, Long ownerUserId,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt,
                          boolean ownerAlsoDrives) {
        if (ownerAlsoDrives && ownerUserId == null) {
            throw new FieldValidationException("owner",
                    "A company cannot drive its own vehicle. Assign a driver instead.");
        }
        // Both optional: the plate and the body type are often unknown when the truck is first
        // recorded. Given, each is still checked; see changeset 023.
        String reg = Normalizer.optionalRegistration(registrationNumber, "registration_number");
        if (reg != null && vehicles.existsByRegistrationNumber(reg)) {
            throw new ApiException.Conflict("Vehicle " + reg + " is already registered.");
        }
        requireExactlyOneOwner(ownerCompanyId, ownerUserId);
        checkBodyType(bodyTypeId);
        validateDimensions(axles, wheels, capacity, lengthFt);

        return ConstraintErrors.translating(() -> {
            Vehicle v = new Vehicle(reg, bodyTypeId);
            v.assignOwner(ownerCompanyId, ownerUserId);
            v.setNoOfAxles(axles);
            v.setNoOfWheels(wheels);
            v.setCapacity(Normalizer.clean(capacity));
            v.setLengthFt(lengthFt);
            vehicles.saveAndFlush(v);

            if (ownerAlsoDrives) {
                // person-owned by definition here, so there is no company to check
                VehicleUser link = new VehicleUser(v.getId(), ownerUserId, null);
                link.setPrimary(true);
                vehicleUsers.saveAndFlush(link);
            }
            return v;
        });
    }

    /**
     * Register a vehicle from what a CSR actually has in front of them: a plate, a driver's name
     * and number, and a company name.
     *
     * <p><b>Why this exists as one call.</b> The alternative is making the office staff create
     * the company, then create the driver, then put the driver on the company's books, then
     * register the truck, then assign the driver — five requests in the right order, four of
     * which they would have to get right before the fifth is allowed. Done by hand that is a
     * transcription error waiting to happen, and half-finished attempts leave orphan companies
     * and drivers behind. Done here it is one transaction: all of it, or none of it.
     *
     * <p>It is also the <b>only</b> way someone who is not an administrator creates a user, and
     * that is deliberate. The scope is "the driver of the truck I am recording", not the run of
     * the directory.
     *
     * <p>Both lookups are find-or-create on the natural key — mobile for a person, the
     * lower-cased name for a company — so entering the same driver for a second truck reuses the
     * record rather than failing on a duplicate, and does not quietly rename them either.
     *
     * <p>Who ends up owning it:
     * <ul>
     *   <li><b>A company name given</b> — the company owns the truck, and the driver is put on
     *       its books before being assigned, because the database will not accept the assignment
     *       otherwise.
     *   <li><b>No company</b> — the driver owns it and drives it: the owner-operator, and most of
     *       Indian trucking.
     * </ul>
     */
    /**
     * Who to ring about this truck, and who owns it.
     *
     * <p>Grouped rather than spread across five parameters because they travel together and are
     * all strings: a caller that transposed {@code driverName} and {@code companyName} in a
     * positional list would compile, register a company called "Suresh Patil", and be found by
     * a telecaller rather than a test.
     *
     * <p><b>Both extra numbers are optional and neither is a second identity.</b> A driver is
     * found by {@code driverMobile} alone — {@code driverAltMobile} is a way to reach the same
     * person, stored on them, never looked up by. A truck's side often carries three numbers and
     * they belong in different places: two on the driver, one on the company.
     */
    public record Contacts(String driverName, String driverMobile, String driverAltMobile,
                           String companyName, String companyMobile) {

        /** The common case: one number, and no company. */
        public static Contacts of(String driverName, String driverMobile) {
            return new Contacts(driverName, driverMobile, null, null, null);
        }

        public static Contacts of(String driverName, String driverMobile, String companyName) {
            return new Contacts(driverName, driverMobile, null, companyName, null);
        }
    }

    @Transactional
    public Vehicle intake(String registrationNumber, Long bodyTypeId, Contacts contacts,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt) {
        return intake(registrationNumber, bodyTypeId, contacts,
                axles, wheels, capacity, lengthFt, List.of());
    }

    /**
     * As {@link #intake}, also recording where the truck runs.
     *
     * <p>Where those places land depends on who ends up owning it, and that is the point of
     * doing it here rather than making the caller work it out: with a company they become the
     * <b>company's</b> locations, which every truck it owns inherits; without one they are the
     * vehicle's own. One question at the desk — "where does this run?" — and the model decides
     * where the answer belongs.
     *
     * <p>Naming a company that already has locations replaces them, so the caller should only
     * send places when it means to set the company's route.
     */
    @Transactional
    public Vehicle intake(String registrationNumber, Long bodyTypeId, Contacts contacts,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt,
                          List<CityRef> places) {
        String cleanDriver = Normalizer.clean(contacts.driverName());
        String cleanCompany = Normalizer.clean(contacts.companyName());

        if (cleanDriver == null) {
            throw new FieldValidationException("driver_name", "Who drives it? A name is required.");
        }
        // Normalising first means the "already on file?" lookup uses the same spelling the table
        // does -- otherwise "+91 98110 08120" creates a second record for an existing driver.
        String mobile = Normalizer.mobile(contacts.driverMobile(), "driver_mobile");
        String altMobile = Normalizer.optionalMobile(contacts.driverAltMobile(),
                "driver_alt_mobile");
        if (altMobile != null && altMobile.equals(mobile)) {
            throw new FieldValidationException("driver_alt_mobile",
                    "That is the same as the driver's main number.");
        }

        User driver = users.findByMobile(mobile).orElse(null);
        if (driver == null) {
            final String alt = altMobile;
            driver = ConstraintErrors.translating(() -> {
                User created = new User(cleanDriver, mobile, UserType.DRIVER);
                created.setAltMobile(alt);
                return users.saveAndFlush(created);
            });
        } else if (!driver.isActive()) {
            throw new ApiException.Conflict(
                    driver.getName() + " is on file against " + mobile
                    + " but has been deactivated. Reactivate them before assigning a vehicle.");
        } else if (altMobile != null && !altMobile.equals(driver.getAltMobile())) {
            // A CSR on the phone has just been told this number, so it wins over whatever was
            // there. A BLANK one does not: leaving the field empty means "I did not ask",
            // not "delete the number you already had".
            driver.setAltMobile(altMobile);
            users.saveAndFlush(driver);
        }

        Company company = null;
        if (cleanCompany != null) {
            String companyMobile = Normalizer.optionalMobile(contacts.companyMobile(),
                    "company_mobile");
            company = companies.findByNameKey(cleanCompany.toLowerCase()).orElse(null);
            if (company == null) {
                final String name = cleanCompany;
                company = ConstraintErrors.translating(() -> {
                    Company created = new Company(name);
                    created.setMobile(companyMobile);
                    return companies.saveAndFlush(created);
                });
            } else if (companyMobile != null && !companyMobile.equals(company.getMobile())) {
                company.setMobile(companyMobile);
                companies.saveAndFlush(company);
            }
        }

        boolean companyOwned = company != null;
        Vehicle vehicle = create(registrationNumber, bodyTypeId,
                companyOwned ? company.getId() : null,
                companyOwned ? null : driver.getId(),
                axles, wheels, capacity, lengthFt,
                // A person-owned truck gets its owner recorded as the driver in one step.
                !companyOwned);

        if (companyOwned) {
            // Employment FIRST. fk_vxu_employed refuses a driver who is not on the owning
            // company's books, so the order here is not a preference -- reverse it and the
            // assignment below is rejected by the database.
            if (!memberships.existsById(new UserCompany.Key(driver.getId(), company.getId()))) {
                UserCompany hire = new UserCompany(driver.getId(), company.getId());
                hire.setPosition("Driver");
                ConstraintErrors.translating(() -> memberships.saveAndFlush(hire));
            }
            addDriver(vehicle.getId(), driver.getId(), true);
        }

        if (places != null && !places.isEmpty()) {
            // The same answer goes to different places depending on who owns the truck, which is
            // exactly the rule the desk should not have to know.
            if (companyOwned) {
                companyLocations(company.getId(), places);
            } else {
                setPreferredLocations(vehicle.getId(), places);
            }
        }
        return vehicle;
    }

    /**
     * Replace a company's locations from here.
     *
     * <p>Duplicated from CompanyService rather than injected, because the two services would
     * otherwise depend on each other in a cycle. The validation is the same and the constraints
     * behind it are the same; this is the shorter of the two evils.
     */
    private void companyLocations(long companyId, List<CityRef> places) {
        companyLocationRepo.deleteByCompanyId(companyId);
        for (CityRef place : places) {
            requireCityInState(place);
            ConstraintErrors.translating(() -> companyLocationRepo.saveAndFlush(
                    new CompanyLocation(companyId, place.stateId(), place.cityId())));
        }
    }

    /**
     * Register a vehicle and say where it runs, in one transaction.
     *
     * <p><b>Only a person-owned vehicle may be given places here.</b> A company's truck runs
     * where the company runs and is forbidden its own preferences — by the database, not merely
     * by convention. Accepting them and silently dropping them would be worse than refusing:
     * whoever filled the form in would believe the truck was routed.
     *
     * <p>The two writes are one transaction, so a rejected location does not leave a vehicle
     * registered with half a route.
     */
    @Transactional
    public Vehicle create(String registrationNumber, Long bodyTypeId,
                          Long ownerCompanyId, Long ownerUserId,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt,
                          boolean ownerAlsoDrives, List<CityRef> places) {
        Vehicle v = create(registrationNumber, bodyTypeId, ownerCompanyId, ownerUserId,
                axles, wheels, capacity, lengthFt, ownerAlsoDrives);
        if (places != null && !places.isEmpty()) {
            setPreferredLocations(v.getId(), places);
        }
        return v;
    }

    /**
     * Move a vehicle to exactly one new owner.
     *
     * <p>The statement order below is load-bearing and is NOT something Hibernate's unit of work
     * will arrive at on its own: a plain flush emits inserts before deletes, so the new OWNER row
     * would go in while the stale one still points at the previous owner, and
     * {@code fk_vxu_owner_matches} would reject it. Hence explicit deletes that reach the
     * database first.
     *
     * <p><b>Every link to the outgoing owner goes, drivers included.</b> A truck sold from one
     * company to an owner-driver must not keep answering "who drives this?" with someone off the
     * previous owner's payroll — that is wrong rather than merely stale. The cost is that an
     * owner-driver who incorporates has to be re-added as a driver: one insert, against a class
     * of silently wrong answers.
     */
    @Transactional
    public Vehicle setOwner(long vehicleId, Long companyId, Long userId) {
        return setOwner(vehicleId, companyId, userId, false);
    }

    /** As {@link #setOwner(long, Long, Long)}, recording the new owner as a driver as well. */
    @Transactional
    public Vehicle setOwner(long vehicleId, Long companyId, Long userId, boolean ownerAlsoDrives) {
        return changeOwner(vehicleId, companyId, userId, ownerAlsoDrives).vehicle();
    }

    /**
     * As {@link #setOwner(long, Long, Long, boolean)}, also reporting how many driver assignments
     * the transfer discarded.
     *
     * <p>The count is returned rather than logged because the caller has to be able to say so: a
     * sale silently emptying the cab list is exactly the kind of side effect an API should not
     * hide. The number is taken from the DELETE itself, not counted separately, so it cannot
     * disagree with what actually happened.
     */
    @Transactional
    public OwnerChange changeOwner(long vehicleId, Long companyId, Long userId,
                                   boolean ownerAlsoDrives) {
        requireExactlyOneOwner(companyId, userId);
        if (ownerAlsoDrives && userId == null) {
            throw new FieldValidationException("owner",
                    "A company cannot drive its own vehicle. Assign a driver instead.");
        }
        Vehicle v = get(vehicleId);
        if (companyId != null && !companies.existsById(companyId)) {
            throw new ApiException.NotFound("Company " + companyId + " not found.");
        }
        if (userId != null && !users.existsById(userId)) {
            throw new ApiException.NotFound("User " + userId + " not found.");
        }

        int removedDrivers = vehicleUsers.deleteByVehicleId(vehicleId);
        if (companyId != null) {
            // fk_vxl_vehicle is ON UPDATE NO ACTION: the owner change below is refused outright
            // while the vehicle still has preferred locations of its own.
            vehicleLocations.deleteByVehicleId(vehicleId);
        }

        Vehicle reloaded = get(vehicleId);   // the deletes above cleared the persistence context
        return ConstraintErrors.translating(() -> {
            reloaded.assignOwner(companyId, userId);
            em.flush();                  // ck_vehicles_one_owner fires here if this is wrong

            if (ownerAlsoDrives) {
                VehicleUser link = new VehicleUser(vehicleId, userId, null);
                link.setPrimary(true);
                vehicleUsers.saveAndFlush(link);
            }
            return new OwnerChange(reloaded, removedDrivers);
        });
    }

    /**
     * Assign a driver.
     *
     * <p><b>A company's truck may only be driven by someone on that company's books.</b> The
     * database enforces that (fk_vxu_employed); the check here exists to say so in words and
     * name the company, rather than letting a foreign-key violation reach the caller.
     *
     * <p>A person-owned vehicle carries no such restriction: an owner-operator may put whoever
     * he likes behind the wheel, and there is no employment record to check against.
     *
     * <p>Idempotent: the primary key is assigned, so saving an existing pair merges rather than
     * inserting. Calling this twice for one person updates their row.
     */
    @Transactional
    public VehicleUser addDriver(long vehicleId, long userId, boolean primary) {
        Vehicle v = get(vehicleId);
        if (!users.existsById(userId)) {
            throw new ApiException.NotFound("User " + userId + " not found.");
        }
        Long companyId = v.getOwnerCompanyId();
        if (companyId != null
                && !memberships.existsById(new UserCompany.Key(userId, companyId))) {
            String company = companies.findById(companyId)
                    .map(Company::getName).orElse("that company");
            throw new ApiException.Conflict(
                    "Only drivers on the books of " + company
                    + " may be assigned to its vehicles. Add this person to " + company
                    + " first.");
        }
        VehicleUser link = new VehicleUser(vehicleId, userId, companyId);
        link.setPrimary(primary);
        // saveAndFlush, not save: uq_vxu_primary_driver would otherwise fire at commit, long
        // after any translation could catch it.
        return ConstraintErrors.translating(() -> vehicleUsers.saveAndFlush(link));
    }

    /**
     * Replace the vehicle's own preferred locations.
     *
     * <p>Refused for a company-owned vehicle — its company's locations apply, and a row here
     * would be a second answer to one question. The database refuses it too; this is the
     * readable version of that refusal.
     *
     * <p>A null city means the whole state.
     */
    @Transactional
    public List<VehicleLocation> setPreferredLocations(long vehicleId, List<CityRef> places) {
        get(vehicleId);
        // No longer refused for a company's truck. Its own locations are ADDITIVE to the
        // company's rather than an alternative to them, so a haulier covering Maharashtra can
        // still say that one particular truck runs the Nagpur-Pune shuttle. Under the old rule
        // the only way to express that was to give the whole company that route, which was
        // wrong about every other truck it owned. See changeset 009.
        vehicleLocations.deleteByVehicleId(vehicleId);
        return ConstraintErrors.translating(() -> {
            for (CityRef place : places) {
                requireCityInState(place);
                vehicleLocations.saveAndFlush(
                        new VehicleLocation(vehicleId, place.stateId(), place.cityId()));
            }
            return vehicleLocations.findByVehicleId(vehicleId);
        });
    }

    /**
     * Where this vehicle runs: its company's locations <b>and</b> its own, resolved by the
     * {@code vehicle_effective_locations} view. Each row says which of the two it came from.
     */
    public List<VehicleRepository.EffectiveLocation> effectiveLocations(long vehicleId) {
        get(vehicleId);
        return vehicles.effectiveLocations(vehicleId);
    }

    public List<Vehicle> servingCity(long cityId) {
        return vehicles.findServingCity(cityId);
    }

    /**
     * Edit the truck itself. Not its owner and not its drivers — those are separate facts with
     * separate rules, and folding them in here would make a typo in the capacity field capable
     * of selling the vehicle.
     *
     * <p>{@code registrationNumber} is how a plate reaches a vehicle registered without one. A
     * blank one leaves the plate as it is rather than clearing it: a plate once known does not
     * become unknown, and older callers do not send the field at all.
     */
    @Transactional
    public Vehicle update(long vehicleId, String registrationNumber, Long bodyTypeId,
                          Short axles, Short wheels, String capacity, BigDecimal lengthFt,
                          String notes) {
        Vehicle v = get(vehicleId);
        String reg = Normalizer.optionalRegistration(registrationNumber, "registration_number");
        if (reg != null && vehicles.existsByRegistrationNumberAndIdNot(reg, vehicleId)) {
            throw new ApiException.Conflict("Vehicle " + reg + " is already registered.");
        }
        checkBodyType(bodyTypeId);
        validateDimensions(axles, wheels, capacity, lengthFt);
        if (reg != null) {
            v.setRegistrationNumber(reg);
        }
        v.setBodyTypeId(bodyTypeId);
        v.setNoOfAxles(axles);
        v.setNoOfWheels(wheels);
        // capacity_tons is a generated column: the database re-derives it from this text, so the
        // two cannot end up disagreeing. Do not add a Java-side mirror.
        v.setCapacity(Normalizer.clean(capacity));
        v.setLengthFt(lengthFt);
        v.setNotes(Normalizer.clean(notes));
        return ConstraintErrors.translating(() -> vehicles.saveAndFlush(v));
    }

    /**
     * Take a vehicle off the road.
     *
     * <p>Not a delete. Its owner is referenced ON DELETE RESTRICT and its links cascade, so a
     * hard delete would quietly discard the driver history of a truck that may come back. An
     * inactive vehicle is excluded from {@code findServingCity}, which is what "off the road"
     * means in practice.
     */
    @Transactional
    public Vehicle deactivate(long vehicleId) {
        Vehicle v = get(vehicleId);
        v.setActive(false);
        return vehicles.saveAndFlush(v);
    }

    @Transactional
    public Vehicle activate(long vehicleId) {
        Vehicle v = get(vehicleId);
        v.setActive(true);
        return vehicles.saveAndFlush(v);
    }

    /** The filtered, paged fleet. A blank {@code q} means no filter, not an empty result. */
    public Page<Vehicle> list(String q, String contact, String owned, Long companyId,
                              Long ownerUserId, Long bodyTypeId, BigDecimal minTons,
                              Long servingCityId, Long servingStateId, Boolean active,
                              Pageable pageable) {
        String clean = Normalizer.clean(q);
        String cleanContact = Normalizer.clean(contact);
        // Anything that is not one of the two known values is no filter at all.
        String kind = "company".equals(owned) || "person".equals(owned) ? owned : null;
        return vehicles.search(clean == null ? null : "%" + clean + "%",
                cleanContact == null ? null : "%" + cleanContact + "%", kind,
                companyId, ownerUserId, bodyTypeId, minTons, servingCityId, servingStateId,
                active, pageable);
    }

    /**
     * Who to ring about each of these vehicles, keyed by vehicle id.
     *
     * <p>One query for the whole page. Empty in, empty out — passing an empty collection to an
     * {@code IN ()} is a syntax error in PostgreSQL, not an empty result.
     */
    public Map<Long, VehicleRepository.Contact> contactsFor(List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return vehicles.contactsFor(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleRepository.Contact::getVehicleId,
                        Function.identity()));
    }

    /**
     * Where each of a page of vehicles runs, summarised. One query, same contract as
     * {@link #contactsFor}: empty in, empty out, because {@code IN ()} is a syntax error.
     */
    public Map<Long, VehicleRepository.LocationSummary> locationsFor(List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return vehicles.locationsFor(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleRepository.LocationSummary::getVehicleId,
                        Function.identity()));
    }

    /** Who drives this, with their directory details resolved in one extra query rather than N. */
    public List<DriverAssignment> driversOf(long vehicleId) {
        get(vehicleId);
        List<VehicleUser> links = vehicleUsers.findByVehicleId(vehicleId);
        Map<Long, User> byId = users
                .findAllById(links.stream().map(VehicleUser::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return links.stream()
                .map(l -> {
                    User u = byId.get(l.getUserId());
                    return new DriverAssignment(l.getUserId(),
                            u == null ? null : u.getName(),
                            u == null ? null : u.getMobile(),
                            l.isPrimary());
                })
                .sorted(Comparator.comparing(DriverAssignment::primary).reversed()
                        .thenComparing(d -> String.valueOf(d.name()),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Unassign a driver. A plain delete: there is no rule about a vehicle having to have one.
     *
     * <p>Note the asymmetry with {@link #addDriver} — adding is checked against the owning
     * company's payroll, removing is not. Taking someone off a truck can never break the rule
     * that only employees drive it.
     */
    @Transactional
    public void removeDriver(long vehicleId, long userId) {
        get(vehicleId);
        if (vehicleUsers.deleteByVehicleIdAndUserId(vehicleId, userId) == 0) {
            throw new ApiException.NotFound(
                    "That person is not recorded as a driver of this vehicle.");
        }
    }

    /**
     * Make one of the existing drivers the primary one.
     *
     * <p>Demotes the current primary first, and that order is load-bearing:
     * {@code uq_vxu_primary_driver} is a partial unique index over the vehicle, so two primaries
     * cannot exist even momentarily. Hibernate left alone would write the promotion first.
     */
    @Transactional
    public VehicleUser setPrimaryDriver(long vehicleId, long userId) {
        get(vehicleId);
        if (!vehicleUsers.existsById(new VehicleUser.Key(vehicleId, userId))) {
            throw new ApiException.NotFound(
                    "That person is not recorded as a driver of this vehicle. Assign them first.");
        }
        vehicleUsers.clearPrimary(vehicleId);
        // clearPrimary clears the persistence context, so this has to be re-read afterwards.
        VehicleUser link = vehicleUsers.findById(new VehicleUser.Key(vehicleId, userId))
                .orElseThrow(() -> new ApiException.NotFound(
                        "That person is not recorded as a driver of this vehicle."));
        link.setPrimary(true);
        return ConstraintErrors.translating(() -> vehicleUsers.saveAndFlush(link));
    }

    // ── validation ──────────────────────────────────────────────────────────────

    private void requireExactlyOneOwner(Long companyId, Long userId) {
        if ((companyId == null) == (userId == null)) {
            throw new FieldValidationException("owner",
                    "A vehicle has exactly one owner: a company or a person, not both and not neither.");
        }
    }

    /** Null is allowed — "not known yet". A given id must be a real, current body type. */
    private void checkBodyType(Long bodyTypeId) {
        if (bodyTypeId == null) {
            return;
        }
        BodyType bt = bodyTypes.findById(bodyTypeId).orElseThrow(
                () -> new FieldValidationException("body_type_id", "That body type does not exist."));
        if (!bt.isActive()) {
            throw new FieldValidationException("body_type_id",
                    bt.getName() + " is retired; pick a current body type.");
        }
    }

    private void requireCityInState(CityRef place) {
        if (place.stateId() == null) {
            throw new FieldValidationException("state_id", "A location needs a state.");
        }
        if (place.cityId() == null) {
            return;                      // whole-state preference; nothing further to check
        }
        City city = cities.findById(place.cityId()).orElseThrow(
                () -> new FieldValidationException("city_id", "That city does not exist."));
        if (!city.getStateId().equals(place.stateId())) {
            throw new FieldValidationException("city_id",
                    city.getName() + " is not in that state.");
        }
    }

    /**
     * The bounds that are NOT database CHECKs, and why.
     *
     * <p>The schema rejects the physically impossible — a 40-axle truck, an odd number of wheels.
     * These are the merely unusual: a required capacity, and a wheels-per-axle ratio. They live
     * here because the first partial third-party import will arrive missing half of them, and a
     * NOT NULL you later relax is a migration you did not need.
     *
     * <p>Axles and wheels are optional: the desk rarely has them on the first call. The ratio
     * is only checked when both are given.
     */
    private void validateDimensions(Short axles, Short wheels, String capacity, BigDecimal lengthFt) {
        if (axles != null && wheels != null && (wheels < axles * 2 || wheels > axles * 4)) {
            throw new FieldValidationException("no_of_wheels",
                    "A " + axles + "-axle vehicle has between " + (axles * 2) + " and "
                    + (axles * 4) + " wheels.");
        }
        if (Normalizer.clean(capacity) == null) {
            throw new FieldValidationException("capacity", "Capacity is required, e.g. \"20 Ton\".");
        }
        BigDecimal tons = Normalizer.capacityTons(capacity);
        if (tons != null && (tons.compareTo(MIN_TONS) < 0 || tons.compareTo(MAX_TONS) > 0)) {
            throw new FieldValidationException("capacity",
                    "Capacity should be between " + MIN_TONS + " and " + MAX_TONS + " tonnes.");
        }
        // Length is optional. The column has always been nullable and `ck_vehicles_length`
        // already bounds it when present; only this validator insisted on it, and a body
        // length is the one dimension nobody reads off the truck at the roadside.
    }

    /** Someone who drives a vehicle, with their directory details resolved. */
    public record DriverAssignment(Long userId, String name, String mobile, boolean primary) {
    }

    /**
     * The result of a sale: the vehicle as it now is, and how many driver assignments went with
     * the previous owner.
     */
    public record OwnerChange(Vehicle vehicle, int removedDrivers) {
    }

    /** A place: a state, and optionally one city inside it. A null city means the whole state. */
    public record CityRef(Long stateId, Long cityId) {
        public static CityRef wholeState(Long stateId) {
            return new CityRef(stateId, null);
        }
    }
}
