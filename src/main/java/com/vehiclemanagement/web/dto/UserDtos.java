package com.vehiclemanagement.web.dto;

import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.service.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Everything the people endpoints send and receive.
 *
 * <p><b>No password hash is ever in here, in either direction.</b> A raw password goes in on
 * {@link LoginRequest} and {@link PasswordChangeRequest} and nothing comes back out — the hash
 * lives between {@code UserService} and the database and is not the client's business.
 */
public final class UserDtos {

    private UserDtos() {
    }

    public record Summary(Long id, String name, String mobile, UserType userType,
                          UserRole role,
                          String username, boolean active) {

        public static Summary from(User u) {
            return new Summary(u.getId(), u.getName(), u.getMobile(), u.getUserType(),
                    u.getRole(), u.getUsername(), u.isActive());
        }
    }

    /**
     * @param canSignIn whether this person has a login at all. Most drivers do not, and that is
     *                  the normal case rather than an incomplete record.
     */
    public record Detail(Long id, String name, String mobile, String altMobile, UserType userType,
                         UserRole role,
                         String email, String username, boolean canSignIn, Long cityId,
                         boolean active, String notes,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static Detail from(User u) {
            return new Detail(u.getId(), u.getName(), u.getMobile(), u.getAltMobile(),
                    u.getUserType(), u.getRole(), u.getEmail(), u.getUsername(), u.canSignIn(),
                    u.getCityId(), u.isActive(), u.getNotes(), u.getCreatedAt(),
                    u.getUpdatedAt());
        }
    }

    public record CreateRequest(@NotBlank String name,
                                @NotBlank String mobile,
                                @NotNull UserType userType) {
    }

    /** Deliberately has no {@code userType}: changing a role is administrator-only, and separate. */
    public record UpdateRequest(@NotBlank String name,
                                @NotBlank String mobile,
                                String altMobile,
                                String email,
                                Long cityId,
                                String notes) {
    }

    /** Changing what somebody IS. Distinct from {@link RoleRequest}, which is what they may do. */
    public record TypeRequest(@NotNull UserType userType) {
    }

    /**
     * Creates or replaces a login. The password is raw; it is hashed in the service.
     *
     * <p>{@code role} is required: a login with no role would be an account that authenticates
     * and can then be granted nothing, and {@code ck_users_role_pair} refuses to store one.
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String password,
                               @NotNull UserRole role) {
    }

    /** Changing what somebody may do, without touching their password. */
    public record RoleRequest(@NotNull UserRole role) {
    }

    public record PasswordChangeRequest(@NotBlank String currentPassword,
                                        @NotBlank String newPassword) {
    }

    public record JoinCompanyRequest(@NotNull Long companyId, String position, boolean primary) {
    }

    public record Membership(Long companyId, String companyName, String position,
                             boolean primary) {

        public static Membership from(UserService.Membership m) {
            return new Membership(m.companyId(), m.companyName(), m.position(), m.primary());
        }
    }

    /**
     * What ending an employment actually did.
     *
     * <p>{@code removedDriverAssignments} is the point of this shape. Leaving a company cascades
     * away every assignment the person had on that company's trucks — the only way "a company's
     * truck takes only a company's driver" stays true without a second manual step. A silent 204
     * would hide it.
     */
    public record LeftCompany(long removedDriverAssignments) {
    }
}
