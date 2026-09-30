package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.security.JwtService;
import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.web.dto.AuthDtos;
import com.vehiclemanagement.web.dto.UserDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Signing in, and what the signed-in caller can see about themselves. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final JwtService tokens;

    public AuthController(UserService users, JwtService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @Operation(summary = "Sign in",
            description = "Returns a bearer token to send as `Authorization: Bearer <token>`. "
                    + "Every failure — unknown username, wrong password, deactivated account, or "
                    + "an account with no login at all — returns the same 401 with the same "
                    + "message, so this endpoint cannot be used to discover which usernames "
                    + "exist. Drivers have no login and can never sign in.")
    @PostMapping("/login")
    public AuthDtos.Session login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        User user = users.authenticate(request.username(), request.password());
        JwtService.Issued issued = tokens.issue(user);
        return new AuthDtos.Session(issued.token(), issued.expiresAt(), UserDtos.Detail.from(user));
    }

    @Operation(summary = "Who am I",
            description = "The account behind the current token, re-read from the database.")
    @GetMapping("/me")
    public UserDtos.Detail me(@AuthenticationPrincipal Jwt jwt) {
        return UserDtos.Detail.from(users.get(Principal.userId(jwt)));
    }

    @Operation(summary = "Change my own password",
            description = "Requires the current password. **Invalidates every token issued "
                    + "before now**, including this one — sign in again afterwards. That is how "
                    + "a stolen token is revoked when there is no session table.")
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody UserDtos.PasswordChangeRequest request) {
        users.changePassword(Principal.userId(jwt), request.currentPassword(),
                request.newPassword());
    }
}
