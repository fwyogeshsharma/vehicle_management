package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserCompany;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.CompanyRepository;
import com.vehiclemanagement.repo.UserCompanyRepository;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.repo.VehicleRepository;
import com.vehiclemanagement.repo.VehicleUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * People: drivers, owners, staff — and, for those who sign in, their login.
 *
 * <p>One table for both. A driver who never signs in simply has no username and no password
 * hash, and the database enforces that it is both or neither (ck_users_login_pair).
 *
 * <p><b>This class owns password hashing.</b> A raw password enters here and a bcrypt hash
 * leaves for the database; nothing above this layer ever sees either. That is why
 * {@link #setLogin} takes a plaintext rather than a hash — an earlier signature took the hash
 * and left every caller free to invent its own, which is how {@code "not-a-real-hash"} ended up
 * in the sample data.
 */
@Service
public class UserService {

    /**
     * Who may hold a login at all.
     *
     * <p>A driver with a password is a driver who can call the API. Kept in Java rather than as a
     * CHECK for the reason the rest of this codebase gives: the set of roles that use the system
     * is a policy that will change, and a CHECK you have to relax costs a migration on a live
     * database while a validator costs a commit.
     */
    private static final Set<UserType> MAY_SIGN_IN = EnumSet.of(UserType.STAFF, UserType.ADMIN);

    private final UserRepository users;
    private final UserCompanyRepository memberships;
    private final CompanyRepository companies;
    private final VehicleUserRepository vehicleUsers;
    private final VehicleRepository vehicles;
    private final CityRepository cities;
    private final PasswordEncoder passwords;

    public UserService(UserRepository users, UserCompanyRepository memberships,
                       CompanyRepository companies, VehicleUserRepository vehicleUsers,
                       VehicleRepository vehicles, CityRepository cities,
                       PasswordEncoder passwords) {
        this.users = users;
        this.memberships = memberships;
        this.companies = companies;
        this.vehicleUsers = vehicleUsers;
        this.vehicles = vehicles;
        this.cities = cities;
        this.passwords = passwords;
    }

    public User get(long id) {
        return users.findById(id).orElseThrow(
                () -> new ApiException.NotFound("User " + id + " not found."));
    }

    /** The filtered directory. A blank {@code q} means no text filter, not an empty result. */
    public Page<User> list(String q, UserType type, Long companyId, Boolean active,
                           Pageable pageable) {
        return users.search(like(q), type == null ? null : type.name(), companyId, active,
                pageable);
    }

    @Transactional
    public User create(String name, String mobile, UserType type) {
        String normalised = Normalizer.mobile(mobile, "mobile");
        if (users.existsByMobile(normalised)) {
            throw new ApiException.Conflict("Someone is already registered on " + normalised + ".");
        }
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "Name is required.");
        }
        return ConstraintErrors.translating(
                () -> users.saveAndFlush(new User(clean, normalised, type)));
    }

    /**
     * Edit the directory entry.
     *
     * <p>Deliberately cannot change {@code user_type}: that is privilege escalation, and it has
     * its own administrator-only path in {@link #setType}.
     */
    @Transactional
    public User update(long id, String name, String mobile, String altMobile, String email,
                       Long cityId, String notes) {
        User u = get(id);
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "Name is required.");
        }
        String normalised = Normalizer.mobile(mobile, "mobile");
        if (users.existsByMobileAndIdNot(normalised, id)) {
            throw new ApiException.Conflict("Someone else is already registered on "
                    + normalised + ".");
        }
        if (cityId != null && !cities.existsById(cityId)) {
            throw new FieldValidationException("city_id", "That city does not exist.");
        }
        u.setName(clean);
        u.setMobile(normalised);
        u.setAltMobile(Normalizer.optionalMobile(altMobile, "alt_mobile"));
        u.setEmail(Normalizer.clean(email));
        u.setCityId(cityId);
        u.setNotes(Normalizer.clean(notes));
        return ConstraintErrors.translating(() -> users.saveAndFlush(u));
    }

    /** Administrator only: this is how someone becomes able to administer the system. */
    @Transactional
    public User setType(long id, UserType type) {
        User u = get(id);
        // No last-administrator guard here any more. Since changeset 029 a type change does
        // not touch authority -- an administrator whose type becomes STAFF still administers,
        // because that is `role`. Guarding this would refuse an edit that costs nobody access.
        // Demotion is setRole, which is guarded.
        if (u.canSignIn() && !MAY_SIGN_IN.contains(type)) {
            throw new ApiException.Conflict(
                    u.getName() + " has a login, and a " + type + " may not sign in. "
                    + "Remove the login first.");
        }
        u.setUserType(type);
        return ConstraintErrors.translating(() -> users.saveAndFlush(u));
    }

    /**
     * Retire a person. Not a delete: vehicles reference their owner ON DELETE RESTRICT, so a hard
     * delete of anyone who has ever owned a truck is refused by the database anyway.
     *
     * <p>Any token they hold stops working on their next request — the authentication converter
     * re-reads {@code is_active} every time, which is the point of paying for that read.
     */
    @Transactional
    public User deactivate(long id) {
        User u = get(id);
        // Unconditionally. Gating this on user_type == ADMIN was right until changeset 029
        // split authority out into `role`: an administrator whose TYPE is STAFF -- now the
        // ordinary case -- skipped the guard entirely, so the last real administrator could
        // be deactivated and nobody could sign in to undo it. requireNotTheLastAdmin checks
        // the role itself and returns immediately for anyone who is not one.
        requireNotTheLastAdmin(u, "deactivate");
        u.setActive(false);
        return users.saveAndFlush(u);
    }

    @Transactional
    public User activate(long id) {
        User u = get(id);
        u.setActive(true);
        return users.saveAndFlush(u);
    }

    /**
     * Give someone a login, or replace the one they have.
     *
     * <p>Takes the <b>raw</b> password and hashes it here. Stamps {@code password_changed_at}, so
     * any token issued before this call is refused from now on.
     */
    @Transactional
    public User setLogin(long userId, String username, String rawPassword, UserRole role) {
        User u = get(userId);
        if (!MAY_SIGN_IN.contains(u.getUserType())) {
            throw new ApiException.Conflict(
                    u.getName() + " is a " + u.getUserType() + " and does not sign in. "
                    + "Only " + MAY_SIGN_IN.stream().map(Enum::name)
                            .collect(Collectors.joining(" and ")) + " accounts have a login.");
        }
        String name = Normalizer.username(username);
        Normalizer.password(rawPassword, "password");
        if (users.existsByUsernameAndIdNot(name, userId)) {
            throw new ApiException.Conflict("Username " + name + " is taken.");
        }
        if (role == null) {
            throw new FieldValidationException("role",
                    "A login needs a role: ADMIN, CSR or TEJJJ_CSR.");
        }
        u.setLogin(name, passwords.encode(rawPassword), role);
        return ConstraintErrors.translating(() -> users.saveAndFlush(u));
    }

    /**
     * Take the login away. The person stays in the directory — they are a driver or a contact
     * who no longer signs in, not someone who was never here.
     */
    /**
     * Change what somebody may do, without touching their password.
     *
     * <p>Separate from {@link #setLogin} because they are different acts: promoting a CSR to
     * administrator should not require knowing or resetting their password, and reissuing a
     * password should not silently be a chance to change their authority.
     *
     * <p><b>Takes effect on the next request, not the next sign-in.</b> Every call re-reads
     * this row and resolves the authority from it, so a demotion is immediate — which is the
     * whole reason the authority is not a claim in the token.
     */
    @Transactional
    public User setRole(long userId, UserRole role) {
        if (role == null) {
            throw new FieldValidationException("role", "Which role?");
        }
        User u = get(userId);
        if (!u.canSignIn()) {
            throw new ApiException.Conflict(
                    u.getName() + " has no login, so there is nothing to set a role on.");
        }
        if (role != UserRole.ADMIN) {
            // This IS the demotion now, so this is where the last-administrator guard belongs.
            requireNotTheLastAdmin(u, "remove administrator access from");
        }
        u.setRole(role);
        return u;
    }

    @Transactional
    public User removeLogin(long userId) {
        User u = get(userId);
        // Unconditionally, for the same reason as deactivate: the guard keys off `role` now.
        requireNotTheLastAdmin(u, "remove the login of");
        u.clearLogin();
        return ConstraintErrors.translating(() -> users.saveAndFlush(u));
    }

    /**
     * Change your own password, proving you know the current one.
     *
     * <p>The stamp moved by {@code setLogin} is what makes this a revocation: every token issued
     * before now, including one taken by whoever prompted the change, stops being accepted.
     */
    @Transactional
    public User changePassword(long userId, String currentPassword, String newPassword) {
        User u = get(userId);
        if (!u.canSignIn() || !passwords.matches(currentPassword, u.getPasswordHash())) {
            throw new ApiException.Unauthorized("That is not your current password.");
        }
        Normalizer.password(newPassword, "new_password");
        // Keeps the role. Changing your own password is not a change of what you may do.
        u.setLogin(u.getUsername(), passwords.encode(newPassword), u.getRole());
        return users.saveAndFlush(u);
    }

    /**
     * Check a username and password.
     *
     * <p>Every failure — unknown username, no login, deactivated, wrong password — returns the
     * same message. Distinguishing them turns the endpoint into an account enumerator.
     *
     * <p>The hash is compared even when there is no user, against a dummy of the same cost, so
     * the response time does not reveal whether the username exists.
     */
    public User authenticate(String username, String rawPassword) {
        String name = username == null ? "" : username.trim().toLowerCase();
        User u = users.findByUsername(name).orElse(null);
        if (u == null || !u.canSignIn() || !u.isActive()) {
            passwords.matches(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
            throw new ApiException.Unauthorized("Wrong username or password.");
        }
        if (!passwords.matches(rawPassword == null ? "" : rawPassword, u.getPasswordHash())) {
            throw new ApiException.Unauthorized("Wrong username or password.");
        }
        return u;
    }

    /** Attach a person to a company. Many-to-many: a driver can be on two companies' books. */
    @Transactional
    public UserCompany joinCompany(long userId, long companyId, String position, boolean primary) {
        get(userId);
        if (!companies.existsById(companyId)) {
            throw new ApiException.NotFound("Company " + companyId + " not found.");
        }
        UserCompany link = new UserCompany(userId, companyId);
        link.setPosition(Normalizer.clean(position));
        link.setPrimary(primary);
        return ConstraintErrors.translating(() -> memberships.saveAndFlush(link));
    }

    /**
     * End an employment, and report what went with it.
     *
     * <p><b>This also removes every assignment the person has on that company's trucks</b>, by
     * {@code fk_vxu_employed ON DELETE CASCADE} — which is the only way "a company's truck takes
     * only a company's driver" stays true without someone remembering a second step. The count is
     * returned rather than swallowed so the caller can say so.
     */
    @Transactional
    public long leaveCompany(long userId, long companyId) {
        get(userId);
        if (!memberships.existsById(new UserCompany.Key(userId, companyId))) {
            throw new ApiException.NotFound(
                    "That person is not on the books of company " + companyId + ".");
        }
        long assignments = vehicleUsers.countByUserIdAndOwnerCompanyId(userId, companyId);
        memberships.deleteMembership(userId, companyId);
        return assignments;
    }

    public List<UserCompany> companiesOf(long userId) {
        return memberships.findByUserId(userId);
    }

    /** As {@link #companiesOf}, with the company names resolved in one extra query, not N. */
    public List<Membership> membershipsOf(long userId) {
        get(userId);
        List<UserCompany> links = memberships.findByUserId(userId);
        Map<Long, Company> byId = companies
                .findAllById(links.stream().map(UserCompany::getCompanyId).toList())
                .stream().collect(Collectors.toMap(Company::getId, Function.identity()));
        return links.stream()
                .map(l -> new Membership(l.getCompanyId(),
                        byId.containsKey(l.getCompanyId())
                                ? byId.get(l.getCompanyId()).getName() : null,
                        l.getPosition(), l.isPrimary()))
                .sorted((a, b) -> String.valueOf(a.companyName())
                        .compareToIgnoreCase(String.valueOf(b.companyName())))
                .toList();
    }

    /** The trucks this person drives — not the ones they own. Those two are different facts. */
    public List<Vehicle> vehiclesDriven(long userId) {
        get(userId);
        return vehicles.findDrivenBy(userId);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    /**
     * A bcrypt hash of nothing in particular.
     *
     * <p>Compared against when the username is unknown, purely so that the failing path costs the
     * same as the succeeding one. Without it, "no such user" returns in microseconds and "wrong
     * password" in tens of milliseconds, and the difference is an account enumerator.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * Refuse to remove the last way into the system.
     *
     * <p>Not a database constraint: it is a count across rows, which a CHECK cannot express,
     * and it is policy rather than physics.
     *
     * <p><b>Counted on {@code role}.</b> It used to count {@code user_type = ADMIN}, which was
     * the same set until changeset 029 separated them and is now the wrong one twice over: it
     * would miss an administrator whose type is STAFF, and it would count somebody whose type
     * says ADMIN but whose role is CSR — letting the genuine last administrator be removed
     * because an impostor made the number look like two.
     */
    private void requireNotTheLastAdmin(User u, String what) {
        if (u.getRole() != UserRole.ADMIN || !u.canSignIn() || !u.isActive()) {
            // Removing somebody who cannot administer anything cannot lock anybody out.
            return;
        }
        if (users.countByRoleAndActiveTrueAndUsernameIsNotNull(UserRole.ADMIN) <= 1) {
            throw new ApiException.Conflict(
                    "Cannot " + what + " the only administrator who can sign in. "
                    + "Give someone else administrator access first.");
        }
    }

    /** A blank search term means "no filter", not "match the empty string". */
    private static String like(String q) {
        String clean = Normalizer.clean(q);
        return clean == null ? null : "%" + clean + "%";
    }

    /** A person's place at a company, with the company named. */
    public record Membership(Long companyId, String companyName, String position,
                             boolean primary) {
    }
}
