package com.vehiclemanagement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published contract, at {@code /swagger-ui.html}.
 *
 * <p>This exists because the UI is a separate project: without a generated spec, the two repos
 * agree on field names by folklore, and the first sign of a mismatch is a broken screen.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI vehicleManagementApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vehicle Management API")
                        .version("0.1.0")
                        .description("""
                                Companies, vehicles, the people attached to them, and where they
                                run.

                                **Authentication.** POST /api/auth/login returns a bearer token;
                                send it as `Authorization: Bearer <token>`. There is no refresh
                                token and no session table — signing in again is the refresh, and
                                logging out is discarding the token. Deactivating an account,
                                changing its password or removing its login each take effect on
                                the next request.

                                **Authorisation.** Any signed-in user may read and write the
                                domain. Administering accounts — granting or removing a login,
                                changing a role, deactivating a person — needs ROLE_ADMIN.
                                Drivers have no login and cannot call this API at all.

                                **DELETE usually deactivates.** Vehicles, people, companies and
                                body types are referenced ON DELETE RESTRICT, so they are retired
                                rather than removed and can be restored. Only link rows — driver
                                assignments, employments, locations — are really deleted.

                                **Errors** are always `{"detail": "..."}`, or
                                `{"detail": [{"loc": ["body", "field"], "msg": "..."}]}` when the
                                problem belongs to one input.
                                """))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
