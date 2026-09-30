package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.BodyType;
import com.vehiclemanagement.domain.City;
import com.vehiclemanagement.domain.State;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** States, cities and body types: the reference data the forms are built out of. */
public final class GeoDtos {

    private GeoDtos() {
    }

    public record StateDto(Long id, String code, String name) {

        public static StateDto from(State s) {
            return new StateDto(s.getId(), s.getCode(), s.getName());
        }
    }

    /**
     * @param active a retired city stays on historic records but is off the pick lists. Exposed
     *               because the masters screen has to be able to show and restore one; without
     *               it a retired city is simply invisible and looks deleted.
     */
    public record CityDto(Long id, Long stateId, String name, boolean active) {

        public static CityDto from(City c) {
            return new CityDto(c.getId(), c.getStateId(), c.getName(), c.isActive());
        }
    }

    /**
     * A rung on the capacity pick list.
     *
     * @param label exactly what is stored in {@code vehicles.capacity} when this is chosen —
     *              there is no formatting step between picking and saving, so the two cannot
     *              drift.
     * @param tons  what it is worth, used only to order the list.
     */
    public record CapacityDto(Long id, String label, java.math.BigDecimal tons, boolean active) {

        public static CapacityDto from(com.vehiclemanagement.domain.Capacity c) {
            return new CapacityDto(c.getId(), c.getLabel(), c.getTons(), c.isActive());
        }
    }

    /**
     * @param active a retired body type. Existing vehicles keep it — it is referenced ON DELETE
     *               RESTRICT — but no new vehicle may be given one.
     */
    public record BodyTypeDto(Long id, String name, boolean active) {

        public static BodyTypeDto from(BodyType b) {
            return new BodyTypeDto(b.getId(), b.getName(), b.isActive());
        }
    }

    public record CapacityRequest(@NotBlank String label,
                                  @NotNull java.math.BigDecimal tons) {
    }

    /** A place the 3,285-row seed did not contain. Always added under a named state. */
    public record CityRequest(@NotBlank String name) {
    }

    public record BodyTypeRequest(@NotBlank String name) {
    }
}
