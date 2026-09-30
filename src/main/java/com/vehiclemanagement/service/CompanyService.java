package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.CompanyLocation;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserCompany;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.CompanyLocationRepository;
import com.vehiclemanagement.repo.CompanyRepository;
import com.vehiclemanagement.repo.UserCompanyRepository;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.repo.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Companies, the people on their books, and the locations they operate in. */
@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final CompanyLocationRepository locations;
    private final CityRepository cities;
    private final UserCompanyRepository memberships;
    private final UserRepository users;
    private final VehicleRepository vehicles;

    public CompanyService(CompanyRepository companies, CompanyLocationRepository locations,
                          CityRepository cities, UserCompanyRepository memberships,
                          UserRepository users, VehicleRepository vehicles) {
        this.companies = companies;
        this.locations = locations;
        this.cities = cities;
        this.memberships = memberships;
        this.users = users;
        this.vehicles = vehicles;
    }

    /**
     * Where each of a page of companies operates. One query; empty in, empty out, because
     * {@code IN ()} is a syntax error in PostgreSQL rather than an empty result.
     */
    public java.util.Map<Long, CompanyRepository.LocationSummary> locationsFor(
            java.util.List<Long> companyIds) {
        if (companyIds.isEmpty()) {
            return java.util.Map.of();
        }
        return companies.locationsFor(companyIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CompanyRepository.LocationSummary::getCompanyId,
                        java.util.function.Function.identity()));
    }

    public Company get(long id) {
        return companies.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Company " + id + " not found."));
    }

    public Page<Company> list(String q, Boolean active, Pageable pageable) {
        String clean = Normalizer.clean(q);
        return companies.search(clean == null ? null : "%" + clean + "%", active, pageable);
    }

    /**
     * Create a company and say where it operates, in one transaction.
     *
     * <p>Separate from {@link #create(String)} rather than replacing it: a company with no
     * locations is a legitimate state, and the seeders and tests create plenty of them. This
     * overload exists because a form that asks for both and then half-saves is worse than one
     * that asks for neither.
     */
    @Transactional
    public Company create(String name, List<VehicleService.CityRef> places) {
        Company created = create(name);
        if (places != null && !places.isEmpty()) {
            setLocations(created.getId(), places);
        }
        return created;
    }

    @Transactional
    public Company create(String name) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "Company name is required.");
        }
        if (companies.existsByNameKey(clean.toLowerCase())) {
            throw new ApiException.Conflict("A company called " + clean + " already exists.");
        }
        return ConstraintErrors.translating(
                () -> companies.saveAndFlush(new Company(clean)));
    }

    @Transactional
    public Company update(long id, String name, String mobile, String email, String gstin,
                          String address, Long headOfficeCityId) {
        Company c = get(id);
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "Company name is required.");
        }
        if (companies.existsByNameKeyAndIdNot(clean.toLowerCase(), id)) {
            throw new ApiException.Conflict("Another company is already called " + clean + ".");
        }
        if (headOfficeCityId != null && !cities.existsById(headOfficeCityId)) {
            throw new FieldValidationException("head_office_city_id", "That city does not exist.");
        }
        c.setName(clean);
        c.setMobile(Normalizer.optionalMobile(mobile, "mobile"));
        c.setEmail(Normalizer.clean(email));
        String cleanGstin = Normalizer.clean(gstin);
        c.setGstin(cleanGstin == null ? null : cleanGstin.toUpperCase());
        c.setAddress(Normalizer.clean(address));
        c.setHeadOfficeCityId(headOfficeCityId);
        return ConstraintErrors.translating(() -> companies.saveAndFlush(c));
    }

    /**
     * Retire a company. Not a delete: its vehicles reference it ON DELETE RESTRICT, so deleting
     * one that still owns a fleet is refused by the database — a fleet does not become ownerless
     * because someone tidied the directory.
     */
    @Transactional
    public Company deactivate(long id) {
        Company c = get(id);
        c.setActive(false);
        return companies.saveAndFlush(c);
    }

    @Transactional
    public Company activate(long id) {
        Company c = get(id);
        c.setActive(true);
        return companies.saveAndFlush(c);
    }

    /**
     * Replace where this company operates.
     *
     * <p>These are what its vehicles inherit — a vehicle owned by a company has no locations of
     * its own, by construction. A company with none serves nowhere; it does not fall back.
     */
    @Transactional
    public List<CompanyLocation> setLocations(long companyId, List<VehicleService.CityRef> places) {
        get(companyId);
        locations.deleteByCompanyId(companyId);
        for (VehicleService.CityRef place : places) {
            if (place.stateId() == null) {
                throw new FieldValidationException("state_id", "A location needs a state.");
            }
            if (place.cityId() != null) {
                City city = cities.findById(place.cityId()).orElseThrow(
                        () -> new FieldValidationException("city_id", "That city does not exist."));
                if (!city.getStateId().equals(place.stateId())) {
                    throw new FieldValidationException("city_id",
                            city.getName() + " is not in that state.");
                }
            }
            ConstraintErrors.translating(() -> locations.saveAndFlush(
                    new CompanyLocation(companyId, place.stateId(), place.cityId())));
        }
        return locations.findByCompanyId(companyId);
    }

    public List<CompanyLocation> locationsOf(long companyId) {
        return locations.findByCompanyId(companyId);
    }

    /** Who is on this company's books — and therefore who may drive its trucks. */
    public List<Member> members(long companyId) {
        get(companyId);
        List<UserCompany> links = memberships.findByCompanyId(companyId);
        Map<Long, User> byId = users
                .findAllById(links.stream().map(UserCompany::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return links.stream()
                .map(l -> {
                    User u = byId.get(l.getUserId());
                    return new Member(l.getUserId(),
                            u == null ? null : u.getName(),
                            u == null ? null : u.getMobile(),
                            u == null ? null : u.getUserType().name(),
                            l.getPosition(), l.isPrimary());
                })
                .sorted((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(
                        String.valueOf(b.name())))
                .toList();
    }

    public List<Vehicle> vehiclesOf(long companyId) {
        get(companyId);
        return vehicles.findByOwnerCompanyIdOrderByRegistrationNumberAsc(companyId);
    }

    /** Someone on a company's books, with their directory details resolved. */
    public record Member(Long userId, String name, String mobile, String userType,
                         String position, boolean primary) {
    }
}
