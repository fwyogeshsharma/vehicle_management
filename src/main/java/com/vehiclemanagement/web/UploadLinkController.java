package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.UploadLink;
import com.vehiclemanagement.security.Principal;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.service.UploadLinkService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/**
 * Administrators hand out, inspect and revoke a person's public upload link.
 *
 * <p>The page that uses the link is {@link PublicUploadController}. Everything here needs a
 * signed-in administrator: a link is a credential, and who may make one is an accounts decision.
 */
@RestController
@RequestMapping("/api/users/{id}/upload-link")
@PreAuthorize(Roles.ADMIN)
public class UploadLinkController {

    /** Never carries the token: it is not stored, so it cannot be shown again. */
    public record Status(boolean exists, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    /** The one response that contains the token. {@code path} is what to append to the site address. */
    public record Issued(String token, String path) {
    }

    private final UploadLinkService links;

    public UploadLinkController(UploadLinkService links) {
        this.links = links;
    }

    @Operation(summary = "Whether this person has an upload link",
            description = "Administrator only. Says whether one exists and when it was last used; "
                    + "the link itself cannot be shown again.")
    @GetMapping
    public Status status(@PathVariable long id) {
        return links.status(id)
                .map(l -> new Status(true, l.getCreatedAt(), l.getLastUsedAt()))
                .orElse(new Status(false, null, null));
    }

    @Operation(summary = "Make a new upload link for this person",
            description = "Administrator only. Replaces any existing link, which stops working at "
                    + "once. The token is in this response and nowhere else, ever.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Issued issue(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        String token = links.generate(id, Principal.userId(jwt));
        return new Issued(token, "/u/" + token);
    }

    @Operation(summary = "Revoke this person's upload link")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        links.revoke(id);
    }
}
