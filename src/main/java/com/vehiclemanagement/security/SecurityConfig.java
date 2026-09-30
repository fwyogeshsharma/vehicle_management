package com.vehiclemanagement.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The filter chain: who may call what, and how a refusal looks.
 *
 * <p><b>The policy, in full.</b> Signing in is enough to read and write the domain — vehicles,
 * companies, people, locations. Administering <em>accounts</em> — creating or removing a login,
 * changing someone's role, deactivating them — takes {@code ROLE_ADMIN}. Drivers are excluded
 * from the API automatically rather than by a rule here: they have no username and no password
 * hash, so there is nothing to authenticate. There is no finer permission model yet, on purpose;
 * see {@link Roles}.
 *
 * <p><b>CSRF is disabled, and that is correct here and only here.</b> The credential is an
 * {@code Authorization} header the browser never attaches on its own, so there is no
 * cross-site request to forge. Were this ever moved to a cookie, CSRF protection would have to
 * come back with it.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final VehicleManagementProperties properties;

    public SecurityConfig(VehicleManagementProperties properties) {
        this.properties = properties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The HMAC key, or a refusal to start.
     *
     * <p>Both a missing secret and a short one fail here rather than being quietly padded.
     * HS256 keys shorter than the 256-bit hash they feed weaken the signature, and a key short
     * enough to be memorable is short enough to be guessed — at which point anyone can mint a
     * token for any account, including the administrator.
     */
    private SecretKeySpec signingKey() {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "No JWT signing secret. Set VM_JWT_SECRET (or vehicle-management.jwt.secret) "
                    + "to at least 32 characters. There is deliberately no default: a "
                    + "development fallback is how a known key reaches production.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "The JWT signing secret is " + bytes.length + " bytes; HS256 needs at least "
                    + "32. Generate one, for example: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        // PATCH is here for PATCH /api/intake/{id}. Omitting a method the app uses fails only in
        // a browser, at the preflight, with a CORS message that names the origin rather than the
        // method — so it reads like a configuration problem on the wrong axis entirely.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        // Nothing rides on a cookie — the token is a header the client attaches deliberately.
        // Leaving this false is also what allows exact origins to stay exact.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserRepository users,
                                           ObjectMapper mapper) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Preflight must pass BEFORE authentication. Without this the browser
                        // gets a 401 on OPTIONS and reports it as a CORS failure, which sends
                        // whoever debugs it to the CORS config, where nothing is wrong.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        // NOTE: /api/intake has no unauthenticated routes. The OCR worker
                        // used to claim work and post results through two permitAll endpoints
                        // gated on a shared secret; it now reads and writes vehicle_intake over
                        // its own PostgreSQL connection, so both are gone. Do not re-add a
                        // permitAll here for a machine caller without re-reading the note in
                        // IntakeController -- an empty-by-default secret plus permitAll is an
                        // open endpoint on any deployment that forgets to set the variable.
                        //
                        // The mobile app's routes (/api/trucks/**) USED to be permitAll, so an
                        // installed copy holding a FreightDesk token could still submit. That
                        // was wrong once the field staff became users of THIS system: with an
                        // anonymous upload accepted, deactivating or deleting somebody did not
                        // stop them uploading -- they only had to drop the Authorization
                        // header. Removing an account has to remove the access, and it cannot
                        // if there is no account on the request.
                        //
                        // So they are authenticated like everything else, and the check that
                        // makes deactivation bite is already there: the converter re-reads the
                        // user row on every request and refuses a deactivated one.
                        //
                        // The cost is that the app must sign in against THIS API
                        // (POST /api/auth/login with a username and password) rather than
                        // carrying a FreightDesk token. The wire shape of the report endpoint
                        // itself is unchanged.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ActiveUserJwtAuthenticationConverter(users)))
                        .authenticationEntryPoint((req, res, ex) ->
                                write(res, mapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        ApiError.NOT_SIGNED_IN))
                        .accessDeniedHandler((req, res, ex) ->
                                write(res, mapper, HttpServletResponse.SC_FORBIDDEN,
                                        ApiError.NOT_ALLOWED)))
                // Same two handlers again for failures raised outside the resource-server filter.
                // Both paths go through write(...) so there is ONE definition of what a 401 and a
                // 403 body look like -- two competing writers is how a client ends up parsing one
                // shape in testing and a different one in production.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                write(res, mapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        ApiError.NOT_SIGNED_IN))
                        .accessDeniedHandler((req, res, e) ->
                                write(res, mapper, HttpServletResponse.SC_FORBIDDEN,
                                        ApiError.NOT_ALLOWED)))
                .build();
    }

    /** The one place a security refusal is turned into a body, in the same shape as every other
     *  error this API returns. */
    private static void write(HttpServletResponse response, ObjectMapper mapper, int status,
                              String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), ApiError.of(message));
    }
}
