package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.repo.CompanyRepository;
import com.vehiclemanagement.domain.CompanyLocation;
import com.vehiclemanagement.service.CompanyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/** Everything the company endpoints send and receive. */
public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record Summary(Long id, String name, String mobile, String gstin, boolean active,
                          /** The first few place names. Null when the company serves nowhere. */
                          String locations,
                          /** How many in total, which may exceed the names shown. */
                          long locationCount) {

        public static Summary from(Company c) {
            return from(c, null);
        }

        public static Summary from(Company c, CompanyRepository.LocationSummary places) {
            return new Summary(c.getId(), c.getName(), c.getMobile(), c.getGstin(), c.isActive(),
                    places == null ? null : places.getSummary(),
                    places == null ? 0 : places.getTotal());
        }
    }

    /**
     * @param headOfficeCityId where the company is registered, <b>not</b> where it operates.
     *                         Its operating area is its locations, which is what its vehicles
     *                         inherit.
     */
    public record Detail(Long id, String name, String mobile, String email, String gstin,
                         String address, Long headOfficeCityId, boolean active,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(Company c) {
            return new Detail(c.getId(), c.getName(), c.getMobile(), c.getEmail(), c.getGstin(),
                    c.getAddress(), c.getHeadOfficeCityId(), c.isActive(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /**
     * @param places where it operates, optional. Every vehicle it owns inherits these, so a
     *               company created without any serves nowhere until they are set.
     */
    public record CreateRequest(@NotBlank String name, List<VehicleDtos.Place> places) {
    }

    public record UpdateRequest(@NotBlank String name, String mobile, String email, String gstin,
                                String address, Long headOfficeCityId) {
    }

    /** A state, optionally narrowed to one city. A null city means the whole state. */
    public record Location(Long id, Long stateId, Long cityId) {

        public static Location from(CompanyLocation l) {
            return new Location(l.getId(), l.getStateId(), l.getCityId());
        }
    }

    /**
     * Replaces the whole set.
     *
     * <p>An empty list means the company serves <b>nowhere</b> — its vehicles then match no
     * city. That is the documented rule, not a fallback to the vehicles' own preferences.
     */
    public record LocationsRequest(@NotNull List<VehicleDtos.Place> places) {
    }

    public record Member(Long userId, String name, String mobile, String userType,
                         String position, boolean primary) {

        public static Member from(CompanyService.Member m) {
            return new Member(m.userId(), m.name(), m.mobile(), m.userType(), m.position(),
                    m.primary());
        }
    }
}
