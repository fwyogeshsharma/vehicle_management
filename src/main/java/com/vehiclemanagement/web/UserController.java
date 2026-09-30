package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.web.dto.UserDtos;
import com.vehiclemanagement.web.dto.VehicleDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Users: drivers, owners, staff and administrators, in one directory.
 *
 * <p><b>Where the administrator line falls.</b> <em>Adding and removing</em> a user is
 * {@code ROLE_ADMIN}, as is everything to do with an account — granting or removing a login,
 * changing a role, deactivating someone. Editing the details of a user who already exists is
 * open to anyone signed in, because correcting a mobile number is ordinary work.
 *
 * <p><b>The one way office staff create a user</b> is {@code POST /api/vehicles}, which takes a
 * driver's name and mobile alongside the truck and creates the record as part of registering it.
 * That is deliberate and scoped: a CSR with a truck in front of them needs its driver on file,
 * and does not need the run of the directory.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Sort keys become raw SQL column names, so nothing outside this list may reach the query. */
    private static final Sorts.Builder SORTS = Sorts
            .allowing("name", "name")
            .and("mobile", "mobile")
            .and("created_at", "created_at")
            .and("id", "id");

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageResponse<UserDtos.Summary> list(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "type", required = false) UserType type,
            @RequestParam(name = "company_id", required = false) Long companyId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "sort", required = false) String sort) {
        Sort order = SORTS.resolve(sort);
        return PageResponse.of(
                users.list(q, type, companyId, active, PageParams.of(page, pageSize, order)),
                UserDtos.Summary::from);
    }

    @GetMapping("/{id}")
    public UserDtos.Detail get(@PathVariable long id) {
        return UserDtos.Detail.from(users.get(id));
    }

    @Operation(summary = "Add a user",
            description = "Administrator only. A user record, not necessarily an account — a "
                    + "DRIVER created here has no login and never gets one. "
                    + "Office staff add drivers through POST /api/vehicles instead, which "
                    + "creates the driver and the company alongside the truck they are being "
                    + "recorded for.")
    @PreAuthorize(Roles.ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDtos.Detail create(@Valid @RequestBody UserDtos.CreateRequest request) {
        return UserDtos.Detail.from(
                users.create(request.name(), request.mobile(), request.userType()));
    }

    @Operation(summary = "Edit a user's details",
            description = "Cannot change their role — that is PUT /{id}/type, and administrator "
                    + "only, because it is privilege escalation.")
    @PutMapping("/{id}")
    public UserDtos.Detail update(@PathVariable long id,
                                  @Valid @RequestBody UserDtos.UpdateRequest request) {
        return UserDtos.Detail.from(users.update(id, request.name(), request.mobile(),
                request.altMobile(), request.email(), request.cityId(), request.notes()));
    }

    @Operation(summary = "Change what someone IS",
            description = "Driver, owner, office staff. **Not** what they may do here — that "
                    + "is `PUT /{id}/role`. Refused if it would leave nobody able to "
                    + "administer the system, or a login on an account type that may not "
                    + "sign in.")
    @PreAuthorize(Roles.ADMIN)
    @PutMapping("/{id}/type")
    public UserDtos.Detail setType(@PathVariable long id,
                                   @Valid @RequestBody UserDtos.TypeRequest request) {
        return UserDtos.Detail.from(users.setType(id, request.userType()));
    }

    @Operation(summary = "Deactivate a user",
            description = "Administrator only. **Deactivates rather than deletes** — a hard "
                    + "delete of anyone who has owned a vehicle is refused by the database "
                    + "anyway. Any token they hold stops working on their next request.")
    @PreAuthorize(Roles.ADMIN)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id) {
        users.deactivate(id);
    }

    @PreAuthorize(Roles.ADMIN)
    @PostMapping("/{id}/restore")
    public UserDtos.Detail restore(@PathVariable long id) {
        return UserDtos.Detail.from(users.activate(id));
    }

    @Operation(summary = "Give someone a login, or replace the one they have",
            description = "Administrator only. Only STAFF and ADMIN may hold a login — a driver "
                    + "with a password is a driver who can call this API. A `role` is required: "
                    + "ADMIN, CSR or TEJJJ_CSR. **Invalidates any token already issued for the "
                    + "account.**")
    @PreAuthorize(Roles.ADMIN)
    @PutMapping("/{id}/login")
    public UserDtos.Detail setLogin(@PathVariable long id,
                                    @Valid @RequestBody UserDtos.LoginRequest request) {
        return UserDtos.Detail.from(
                users.setLogin(id, request.username(), request.password(), request.role()));
    }

    @Operation(summary = "Change what someone MAY DO",
            description = "ADMIN, CSR or TEJJJ_CSR. Administrator only, and separate from the "
                    + "password: promoting a CSR should not require resetting their login. "
                    + "**Takes effect on their next request**, not their next sign-in — the "
                    + "authority is read from the row, never from the token. CSR and TEJJJ_CSR "
                    + "currently carry the same permissions.")
    @PreAuthorize(Roles.ADMIN)
    @PutMapping("/{id}/role")
    public UserDtos.Detail setRole(@PathVariable long id,
                                   @Valid @RequestBody UserDtos.RoleRequest request) {
        return UserDtos.Detail.from(users.setRole(id, request.role()));
    }

    @Operation(summary = "Take a login away",
            description = "The person stays in the directory; they just stop signing in. Their "
                    + "current token stops working immediately.")
    @PreAuthorize(Roles.ADMIN)
    @DeleteMapping("/{id}/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLogin(@PathVariable long id) {
        users.removeLogin(id);
    }

    @GetMapping("/{id}/companies")
    public List<UserDtos.Membership> companies(@PathVariable long id) {
        return users.membershipsOf(id).stream().map(UserDtos.Membership::from).toList();
    }

    @Operation(summary = "Put a user on a company's books",
            description = "Employment is what makes them eligible to drive that company's "
                    + "trucks. Idempotent: re-posting updates the existing row.")
    @PostMapping("/{id}/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public List<UserDtos.Membership> joinCompany(
            @PathVariable long id, @Valid @RequestBody UserDtos.JoinCompanyRequest request) {
        users.joinCompany(id, request.companyId(), request.position(), request.primary());
        return companies(id);
    }

    @Operation(summary = "End an employment",
            description = "**Also removes every assignment this person has on that company's "
                    + "trucks**, and reports how many. That cascade is the only thing keeping "
                    + "\"only a company's own drivers ride its trucks\" true without a second "
                    + "manual step.")
    @DeleteMapping("/{id}/companies/{companyId}")
    public UserDtos.LeftCompany leaveCompany(@PathVariable long id, @PathVariable long companyId) {
        return new UserDtos.LeftCompany(users.leaveCompany(id, companyId));
    }

    @Operation(summary = "The trucks this person drives",
            description = "Driving, not owning — two different facts. For what they own, filter "
                    + "GET /api/vehicles by owner_user_id.")
    @GetMapping("/{id}/vehicles")
    public List<VehicleDtos.Summary> vehicles(@PathVariable long id) {
        return users.vehiclesDriven(id).stream().map(VehicleDtos.Summary::from).toList();
    }
}
