package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.repo.VehicleRepository;
import com.vehiclemanagement.service.VehicleService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the vehicle endpoints send and receive.
 *
 * <p>Nested records in one holder rather than a file each: they are read together, they change
 * together, and a package of thirty one-record files is harder to scan than one class.
 *
 * <p>Field names go out as snake_case through Jackson's naming strategy, configured once in
 * application.yml. Do not annotate them individually.
 */
public final class VehicleDtos {

    private VehicleDtos() {
    }

    /**
     * The list row. Deliberately smaller than {@link Detail} — a page of 200 carries no notes.
     *
     * @param contactName  who to ring: the primary driver, else the owner-driver, else the
     *                     owning company. Null when nobody on the chain has a number on file.
     * @param contactRole  DRIVER, OWNER or COMPANY — which of those it is, because "call this
     *                     number" means something different for each.
     */
    public record Summary(Long id, String registrationNumber, Long bodyTypeId, String capacity,
                          BigDecimal capacityTons, Long ownerCompanyId, Long ownerUserId,
                          boolean companyOwned, boolean active,
                          String contactName, String contactMobile, String contactRole,
                          Short noOfAxles, Short noOfWheels, BigDecimal lengthFt,
                          /** The first few place names, comma separated. Null when it serves nowhere. */
                          String locations,
                          /** How many there are in total, which may exceed the names shown. */
                          long locationCount) {

        public static Summary from(Vehicle v) {
            return from(v, null, null);
        }

        public static Summary from(Vehicle v, VehicleRepository.Contact contact) {
            return from(v, contact, null);
        }

        public static Summary from(Vehicle v, VehicleRepository.Contact contact,
                                   VehicleRepository.LocationSummary places) {
            return new Summary(v.getId(), v.getRegistrationNumber(), v.getBodyTypeId(),
                    v.getCapacity(), v.getCapacityTons(), v.getOwnerCompanyId(),
                    v.getOwnerUserId(), v.isCompanyOwned(), v.isActive(),
                    contact == null ? null : contact.getContactName(),
                    contact == null ? null : contact.getContactMobile(),
                    contact == null ? null : contact.getContactRole(),
                    v.getNoOfAxles(), v.getNoOfWheels(), v.getLengthFt(),
                    places == null ? null : places.getSummary(),
                    places == null ? 0 : places.getTotal());
        }
    }

    public record Detail(Long id, String registrationNumber, Long bodyTypeId, Short noOfAxles,
                         Short noOfWheels, String capacity, BigDecimal capacityTons,
                         BigDecimal lengthFt, Long ownerCompanyId, Long ownerUserId,
                         boolean companyOwned, boolean active, String notes,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(Vehicle v) {
            return new Detail(v.getId(), v.getRegistrationNumber(), v.getBodyTypeId(),
                    v.getNoOfAxles(), v.getNoOfWheels(), v.getCapacity(), v.getCapacityTons(),
                    v.getLengthFt(), v.getOwnerCompanyId(), v.getOwnerUserId(),
                    v.isCompanyOwned(), v.isActive(), v.getNotes(),
                    v.getCreatedAt(), v.getUpdatedAt());
        }
    }

    /**
     * @param ownerAlsoDrives records the owner as a driver too — the owner-operator, and most of
     *                        Indian trucking. Explicit rather than guessed from the person's
     *                        type, because an owner who employs a driver is just as real.
     */
    public record CreateRequest(/** Optional: the plate may not be known yet. */
                                String registrationNumber,
                                /** Optional: null means "not known yet". */
                                Long bodyTypeId,
                                Long ownerCompanyId,
                                Long ownerUserId,
                                boolean ownerAlsoDrives,
                                Short noOfAxles,
                                Short noOfWheels,
                                /** Free text. Ignored when {@code capacityId} is given. */
                                String capacity,
                                /** A pick-list entry; stored on the vehicle as its label. */
                                Long capacityId,
                                BigDecimal lengthFt,
                                /**
                                 * Where it runs. Only for a person-owned vehicle — a company's
                                 * truck inherits its company's, and sending places for one is a
                                 * 422 rather than a silent no-op.
                                 */
                                List<Place> places) {
    }

    /**
     * Registering a truck from what the person at the desk actually has.
     *
     * <p>Names and a mobile number instead of ids, because a CSR taking down a truck has the
     * driver in front of them and has never heard of a user id. The driver is matched on the
     * mobile and the company on its name; either is created if it is new.
     *
     * <p>Leave {@code companyName} out and the driver owns the truck — the owner-operator.
     */
    public record IntakeRequest(/** Optional: the plate may not be known yet. */
                                String registrationNumber,
                                /** Optional: null means "not known yet". */
                                Long bodyTypeId,
                                @NotBlank String driverName,
                                @NotBlank String driverMobile,
                                /**
                                 * A second way to reach the same driver. Optional, and never
                                 * used to look anyone up — the main number is the identity.
                                 */
                                String driverAltMobile,
                                String companyName,
                                /** The office number, stored on the company. Optional. */
                                String companyMobile,
                                Short noOfAxles,
                                Short noOfWheels,
                                /** Free text. Ignored when {@code capacityId} is given. */
                                String capacity,
                                /** A pick-list entry; stored on the vehicle as its label. */
                                Long capacityId,
                                BigDecimal lengthFt,
                                /**
                                 * Where it runs. With a company name these become the
                                 * <b>company's</b> locations, which all its trucks inherit;
                                 * without one they are the vehicle's own.
                                 */
                                List<Place> places) {
    }

    /** The truck's own attributes. Owner and drivers have their own endpoints. */
    public record UpdateRequest(/**
                                 * Optional. Blank or absent leaves the plate as it is: this is
                                 * how a plate is added to a vehicle registered without one, not
                                 * a way to remove it.
                                 */
                                String registrationNumber,
                                /** Optional: null means "not known yet". */
                                Long bodyTypeId,
                                Short noOfAxles,
                                Short noOfWheels,
                                /** Free text. Ignored when {@code capacityId} is given. */
                                String capacity,
                                /** A pick-list entry; stored on the vehicle as its label. */
                                Long capacityId,
                                BigDecimal lengthFt,
                                String notes) {
    }

    /** Exactly one of {@code companyId} / {@code userId}. The database refuses the rest. */
    public record OwnerRequest(Long companyId, Long userId, boolean ownerAlsoDrives) {
    }

    /**
     * The result of a sale.
     *
     * <p>{@code removedDrivers} is not incidental: a transfer clears every driver assignment,
     * because a truck sold by one company must not keep answering "who drives this?" with
     * someone off the previous owner's payroll. The count is reported rather than hidden.
     */
    public record OwnerChanged(Detail vehicle, int removedDrivers) {

        public static OwnerChanged from(VehicleService.OwnerChange change) {
            return new OwnerChanged(Detail.from(change.vehicle()), change.removedDrivers());
        }
    }

    public record DriverRequest(@NotNull Long userId, boolean primary) {
    }

    public record Driver(Long userId, String name, String mobile, boolean primary) {

        public static Driver from(VehicleService.DriverAssignment d) {
            return new Driver(d.userId(), d.name(), d.mobile(), d.primary());
        }
    }

    /**
     * Where a vehicle runs, as resolved by the {@code vehicle_effective_locations} view.
     *
     * @param source COMPANY or VEHICLE — which side of the fallback answered. A company-owned
     *               vehicle inherits its company's locations and has none of its own.
     * @param cityName null means the whole state, which matches every city in it.
     */
    public record Location(Long stateId, String stateName, Long cityId, String cityName,
                           String source) {

        public static Location from(VehicleRepository.EffectiveLocation l) {
            return new Location(l.getStateId(), l.getStateName(), l.getCityId(), l.getCityName(),
                    l.getSource());
        }
    }

    /** Replaces the whole set. An empty list clears it. */
    public record LocationsRequest(@NotNull List<Place> places) {
    }

    /** A state, and optionally one city inside it. A null city means the whole state. */
    public record Place(@NotNull Long stateId, Long cityId) {

        public VehicleService.CityRef toRef() {
            return new VehicleService.CityRef(stateId, cityId);
        }
    }
}
