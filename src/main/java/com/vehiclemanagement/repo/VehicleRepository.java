package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.Vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumberAndIdNot(String registrationNumber, Long id);

    List<Vehicle> findByOwnerCompanyIdOrderByRegistrationNumberAsc(Long companyId);

    List<Vehicle> findByOwnerUserIdOrderByRegistrationNumberAsc(Long userId);

    /**
     * Every active vehicle that serves this city.
     *
     * <p>Two ways to be served: a preference row naming this city, or one naming only its state,
     * which matches every city in that state. Written as a UNION of those two branches rather
     * than one {@code OR} across the view — measured at 20,000 vehicles and 21,000 preference
     * rows, the OR form makes the planner materialise the whole view (a sequential scan of
     * 18,000 rows) and took 221-253ms; this form takes 102-156ms for the identical result,
     * because each branch can be satisfied on its own index.
     *
     * <p>Both read {@code vehicle_effective_locations}, which is where the company-else-own
     * fallback is defined — so this and {@link #effectiveLocations} cannot drift apart. There is
     * one implementation of the rule and it is in the view.
     */
    @Query(value = """
            SELECT v.* FROM vehicles v
             WHERE v.is_active AND v.id IN (
                   SELECT l.vehicle_id FROM vehicle_effective_locations l
                    WHERE l.city_id = :cityId
                   UNION
                   SELECT l.vehicle_id FROM vehicle_effective_locations l
                    WHERE l.city_id IS NULL
                      AND l.state_id = (SELECT state_id FROM cities WHERE id = :cityId))
             ORDER BY v.registration_number
            """, nativeQuery = true)
    List<Vehicle> findServingCity(@Param("cityId") Long cityId);

    /** As {@link #findServingCity}, for a whole state. */
    @Query(value = """
            SELECT DISTINCT v.* FROM vehicles v
              JOIN vehicle_effective_locations l ON l.vehicle_id = v.id
             WHERE v.is_active AND l.state_id = :stateId
             ORDER BY v.registration_number
            """, nativeQuery = true)
    List<Vehicle> findServingState(@Param("stateId") Long stateId);

    /**
     * Where this vehicle runs: its company's locations if a company owns it, otherwise its own.
     *
     * <p>A company with no locations of its own yields an empty list. It does NOT fall back to
     * the vehicle's rows — there are none, because the database forbids them while a company
     * owns it. "Serves nowhere" is the honest answer, and it will be reported as a bug.
     */
    @Query(value = """
            SELECT l.state_id   AS stateId,
                   s.name       AS stateName,
                   l.city_id    AS cityId,
                   c.name       AS cityName,
                   l.source     AS source
              FROM vehicle_effective_locations l
              JOIN states s ON s.id = l.state_id
              LEFT JOIN cities c ON c.id = l.city_id
             WHERE l.vehicle_id = :vehicleId
             ORDER BY s.name, c.name NULLS FIRST
            """, nativeQuery = true)
    List<EffectiveLocation> effectiveLocations(@Param("vehicleId") Long vehicleId);

    /**
     * The filtered, paged vehicle list behind {@code GET /api/vehicles}.
     *
     * <p><b>Every parameter is CAST before the null test.</b> PostgreSQL cannot infer the type of
     * a bare parameter in {@code :p IS NULL} and fails the whole statement with "could not
     * determine data type of parameter" -- which looks like a driver bug and is not. The casts
     * are load-bearing, not decoration.
     *
     * <p>The {@code servingCityId} branch keeps the UNION shape of {@link #findServingCity}
     * rather than an OR across the view: measured at 20,000 vehicles the OR form makes the
     * planner scan the whole view (221-253ms) against 102-156ms for this one, identical result.
     */
    @Query(value = """
            SELECT v.* FROM vehicles v
             WHERE (CAST(:q AS TEXT) IS NULL OR v.registration_number ILIKE CAST(:q AS TEXT))
               AND (CAST(:companyId AS BIGINT) IS NULL OR v.owner_company_id = CAST(:companyId AS BIGINT))
               AND (CAST(:ownerUserId AS BIGINT) IS NULL OR v.owner_user_id = CAST(:ownerUserId AS BIGINT))
               AND (CAST(:bodyTypeId AS BIGINT) IS NULL OR v.body_type_id = CAST(:bodyTypeId AS BIGINT))
               AND (CAST(:minTons AS NUMERIC) IS NULL OR v.capacity_tons >= CAST(:minTons AS NUMERIC))
               AND (CAST(:active AS BOOLEAN) IS NULL OR v.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:servingCityId AS BIGINT) IS NULL OR v.id IN (
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.city_id = CAST(:servingCityId AS BIGINT)
                     UNION
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.city_id IS NULL
                        AND l.state_id = (SELECT state_id FROM cities
                                           WHERE id = CAST(:servingCityId AS BIGINT))))
               AND (CAST(:servingStateId AS BIGINT) IS NULL OR v.id IN (
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.state_id = CAST(:servingStateId AS BIGINT)))
               AND (CAST(:contact AS TEXT) IS NULL OR v.id IN (
                     SELECT v2.id FROM vehicles v2
                      LEFT JOIN vehicle_x_user vu ON vu.vehicle_id = v2.id
                      LEFT JOIN users du     ON du.id = vu.user_id
                      LEFT JOIN users ou     ON ou.id = v2.owner_user_id
                      LEFT JOIN companies co ON co.id = v2.owner_company_id
                      WHERE du.mobile LIKE CAST(:contact AS TEXT)
                         OR ou.mobile LIKE CAST(:contact AS TEXT)
                         OR co.mobile LIKE CAST(:contact AS TEXT)
                         OR du.name ILIKE CAST(:contact AS TEXT)
                         OR ou.name ILIKE CAST(:contact AS TEXT)
                         OR co.name ILIKE CAST(:contact AS TEXT)))
               AND (CAST(:owned AS TEXT) IS NULL
                    OR (CAST(:owned AS TEXT) = 'company' AND v.owner_company_id IS NOT NULL)
                    OR (CAST(:owned AS TEXT) = 'person'  AND v.owner_user_id IS NOT NULL))
            """,
            countQuery = """
            SELECT count(*) FROM vehicles v
             WHERE (CAST(:q AS TEXT) IS NULL OR v.registration_number ILIKE CAST(:q AS TEXT))
               AND (CAST(:companyId AS BIGINT) IS NULL OR v.owner_company_id = CAST(:companyId AS BIGINT))
               AND (CAST(:ownerUserId AS BIGINT) IS NULL OR v.owner_user_id = CAST(:ownerUserId AS BIGINT))
               AND (CAST(:bodyTypeId AS BIGINT) IS NULL OR v.body_type_id = CAST(:bodyTypeId AS BIGINT))
               AND (CAST(:minTons AS NUMERIC) IS NULL OR v.capacity_tons >= CAST(:minTons AS NUMERIC))
               AND (CAST(:active AS BOOLEAN) IS NULL OR v.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:servingCityId AS BIGINT) IS NULL OR v.id IN (
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.city_id = CAST(:servingCityId AS BIGINT)
                     UNION
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.city_id IS NULL
                        AND l.state_id = (SELECT state_id FROM cities
                                           WHERE id = CAST(:servingCityId AS BIGINT))))
               AND (CAST(:servingStateId AS BIGINT) IS NULL OR v.id IN (
                     SELECT l.vehicle_id FROM vehicle_effective_locations l
                      WHERE l.state_id = CAST(:servingStateId AS BIGINT)))
               AND (CAST(:contact AS TEXT) IS NULL OR v.id IN (
                     SELECT v2.id FROM vehicles v2
                      LEFT JOIN vehicle_x_user vu ON vu.vehicle_id = v2.id
                      LEFT JOIN users du     ON du.id = vu.user_id
                      LEFT JOIN users ou     ON ou.id = v2.owner_user_id
                      LEFT JOIN companies co ON co.id = v2.owner_company_id
                      WHERE du.mobile LIKE CAST(:contact AS TEXT)
                         OR ou.mobile LIKE CAST(:contact AS TEXT)
                         OR co.mobile LIKE CAST(:contact AS TEXT)
                         OR du.name ILIKE CAST(:contact AS TEXT)
                         OR ou.name ILIKE CAST(:contact AS TEXT)
                         OR co.name ILIKE CAST(:contact AS TEXT)))
               AND (CAST(:owned AS TEXT) IS NULL
                    OR (CAST(:owned AS TEXT) = 'company' AND v.owner_company_id IS NOT NULL)
                    OR (CAST(:owned AS TEXT) = 'person'  AND v.owner_user_id IS NOT NULL))
            """, nativeQuery = true)
    /**
     * @param owned   'company' or 'person' — which KIND of owner, as opposed to
     *                {@code companyId}/{@code ownerUserId} which name a specific one. Anything
     *                else is ignored rather than rejected: this is a list filter, and a stale
     *                bookmark should show everything rather than fail.
     * @param contact matches the driver's, owner's or company's <b>mobile or name</b>. One
     *                parameter for all six because a dispatcher holding a phone number does not
     *                know which of the three it belongs to — that is the question they are
     *                asking, not one they can answer first.
     */
    Page<Vehicle> search(@Param("q") String q,
                         @Param("contact") String contact,
                         @Param("owned") String owned,
                         @Param("companyId") Long companyId,
                         @Param("ownerUserId") Long ownerUserId,
                         @Param("bodyTypeId") Long bodyTypeId,
                         @Param("minTons") BigDecimal minTons,
                         @Param("servingCityId") Long servingCityId,
                         @Param("servingStateId") Long servingStateId,
                         @Param("active") Boolean active,
                         Pageable pageable);

    /** Every vehicle this person is recorded as driving -- not the ones they own. */
    @Query(value = """
            SELECT v.* FROM vehicles v
              JOIN vehicle_x_user vu ON vu.vehicle_id = v.id
             WHERE vu.user_id = :userId
             ORDER BY v.registration_number
            """, nativeQuery = true)
    List<Vehicle> findDrivenBy(@Param("userId") Long userId);

    /**
     * Who to ring about each of these vehicles.
     *
     * <p>Asked for a page of vehicle ids in one query rather than per row: the list shows a
     * contact for every truck, and doing it per row is the N+1 this codebase avoids by using
     * plain FK columns everywhere else.
     *
     * <p>The fallback order is the order a dispatcher would try. The primary driver is who
     * actually has the keys; failing that the owner-driver is the same person by another name;
     * failing that the owning company's switchboard. A truck with no driver and a company that
     * never filled in a number yields nulls, which is the honest answer.
     */
    @Query(value = """
            SELECT v.id                                        AS vehicleId,
                   COALESCE(du.name, ou.name, co.name)         AS contactName,
                   COALESCE(du.mobile, ou.mobile, co.mobile)   AS contactMobile,
                   CASE WHEN du.id IS NOT NULL THEN 'DRIVER'
                        WHEN ou.id IS NOT NULL THEN 'OWNER'
                        WHEN co.id IS NOT NULL THEN 'COMPANY'
                        ELSE NULL END                          AS contactRole
              FROM vehicles v
              LEFT JOIN vehicle_x_user vu ON vu.vehicle_id = v.id AND vu.is_primary
              LEFT JOIN users du     ON du.id = vu.user_id
              LEFT JOIN users ou     ON ou.id = v.owner_user_id
              LEFT JOIN companies co ON co.id = v.owner_company_id
             WHERE v.id IN (:ids)
            """, nativeQuery = true)
    List<Contact> contactsFor(@Param("ids") java.util.Collection<Long> ids);

    /**
     * Where each of these vehicles runs, one row per vehicle, already summarised.
     *
     * <p>Batched over a page of ids for the same reason {@link #contactsFor} is: a list showing
     * locations for twenty trucks must not be twenty queries.
     *
     * <p>Summarised in SQL rather than in Java because the list only ever shows the first few
     * and a count — "Ludhiana, Jaipur +3". Sending every row of a company with forty locations
     * so the client can show two would make the page heavier than the data it displays.
     *
     * <p>Reads the {@code vehicle_effective_locations} view, so a company-owned truck reports
     * the company's locations and an owner-driver's reports its own, without the caller needing
     * to know which case it is in.
     */
    @Query(value = """
            SELECT vehicle_id                                            AS vehicleId,
                   count(*)                                              AS total,
                   string_agg(label, ', ' ORDER BY label)
                       FILTER (WHERE rn <= 3)                            AS summary
              FROM (
                    SELECT vehicle_id, label,
                           row_number() OVER (PARTITION BY vehicle_id ORDER BY label) AS rn
                      FROM (
                            -- DISTINCT because the view is additive since changeset 009: a truck
                            -- that names Nagpur while its company also names Nagpur produces two
                            -- rows, differing only in `source`. Two claims, one place -- and this
                            -- column reports places, so "Nagpur, Nagpur +1" would be nonsense.
                            SELECT DISTINCT el.vehicle_id,
                                   COALESCE(c.name, s.name || ' (all)') AS label
                              FROM vehicle_effective_locations el
                              JOIN states s      ON s.id = el.state_id
                              LEFT JOIN cities c ON c.id = el.city_id
                             WHERE el.vehicle_id IN (:ids)
                           ) places
                   ) ranked
             GROUP BY vehicle_id
            """, nativeQuery = true)
    List<LocationSummary> locationsFor(@Param("ids") java.util.Collection<Long> ids);

    /** Projection for {@link #locationsFor}. */
    interface LocationSummary {
        Long getVehicleId();

        /** Every location, not just the ones named in {@link #getSummary()}. */
        long getTotal();

        /** The first three by name, comma separated. A state with no city reads "Punjab (all)". */
        String getSummary();
    }

    /** Projection for {@link #contactsFor}. Every field but the id may be null. */
    interface Contact {
        Long getVehicleId();

        String getContactName();

        String getContactMobile();

        /** DRIVER, OWNER or COMPANY -- which fallback answered. */
        String getContactRole();
    }

    /** Projection for {@link #effectiveLocations}. A null cityName means the whole state. */
    interface EffectiveLocation {
        Long getStateId();

        String getStateName();

        Long getCityId();

        String getCityName();

        /** COMPANY or VEHICLE — which side of the fallback answered. */
        String getSource();
    }
}
