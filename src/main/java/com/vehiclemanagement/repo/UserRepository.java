package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Display names for a set of user ids, in one query. For list endpoints that show who did
     * something on every row: looking each one up would be the N+1 this codebase avoids.
     * Nulls are ignored; an id with no row is simply absent from the map.
     */
    default Map<Long, String> namesById(Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return findAllById(wanted).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    /**
     * How many people can actually administer the system right now.
     *
     * <p>Counted on {@code role}, not {@code user_type}. Those were the same thing until
     * changeset 029, and a guard that kept counting types would protect the wrong set: it
     * would let the last real administrator go because somebody with {@code user_type=ADMIN}
     * and {@code role=CSR} still looked like one.
     */
    long countByRoleAndActiveTrueAndUsernameIsNotNull(com.vehiclemanagement.domain.UserRole role);

    Optional<User> findByMobile(String mobile);

    Optional<User> findByUsername(String username);

    boolean existsByMobile(String mobile);

    boolean existsByMobileAndIdNot(String mobile, Long id);

    boolean existsByUsernameAndIdNot(String username, Long id);

    /** Whether anyone can already sign in as an administrator -- the bootstrap-admin guard. */
    boolean existsByUserTypeAndUsernameIsNotNull(UserType userType);

    /**
     * The filtered, paged directory behind {@code GET /api/users}.
     *
     * <p>Casts before every null test: PostgreSQL cannot infer the type of a bare parameter in
     * {@code :p IS NULL}, and without them the statement fails outright.
     */
    @Query(value = """
            SELECT u.* FROM users u
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR u.name ILIKE CAST(:q AS TEXT) OR u.mobile ILIKE CAST(:q AS TEXT))
               AND (CAST(:type AS TEXT) IS NULL OR u.user_type = CAST(:type AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR u.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:companyId AS BIGINT) IS NULL OR EXISTS (
                     SELECT 1 FROM user_x_company uc
                      WHERE uc.user_id = u.id AND uc.company_id = CAST(:companyId AS BIGINT)))
            """,
            countQuery = """
            SELECT count(*) FROM users u
             WHERE (CAST(:q AS TEXT) IS NULL
                    OR u.name ILIKE CAST(:q AS TEXT) OR u.mobile ILIKE CAST(:q AS TEXT))
               AND (CAST(:type AS TEXT) IS NULL OR u.user_type = CAST(:type AS TEXT))
               AND (CAST(:active AS BOOLEAN) IS NULL OR u.is_active = CAST(:active AS BOOLEAN))
               AND (CAST(:companyId AS BIGINT) IS NULL OR EXISTS (
                     SELECT 1 FROM user_x_company uc
                      WHERE uc.user_id = u.id AND uc.company_id = CAST(:companyId AS BIGINT)))
            """, nativeQuery = true)
    Page<User> search(@Param("q") String q,
                      @Param("type") String type,
                      @Param("companyId") Long companyId,
                      @Param("active") Boolean active,
                      Pageable pageable);
}
