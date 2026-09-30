package com.vehiclemanagement;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

/**
 * Base for every test that touches the database — which is nearly all of them.
 *
 * <p><b>Never H2.</b> This schema is built out of {@code num_nonnulls}, stored generated columns,
 * partial indexes, {@code UNIQUE NULLS NOT DISTINCT} and the {@code ~} regex operator, and H2 has
 * none of them. An H2 suite would go green while testing a database with none of the constraints
 * these tests exist to prove — the worst possible outcome, because it would look like coverage.
 *
 * <p>Two ways to get a real PostgreSQL, in this order:
 *
 * <ol>
 *   <li><b>{@code VM_TEST_DATABASE_URL}</b>, with {@code VM_TEST_DATABASE_USER} and
 *       {@code VM_TEST_DATABASE_PASSWORD}. Point it at a scratch database — Liquibase runs
 *       against it and the tests write and delete. Deliberately a separate variable from the application's
 *       {@code VM_DATABASE_URL}, so having your working database exported cannot cause an accident.
 *   <li><b>Testcontainers</b>, when Docker is available. Nothing to set up; one container for the
 *       whole run.
 * </ol>
 *
 * <p>With neither, the database tests are <em>skipped with a reason</em> rather than passing
 * vacuously: a bare {@code mvn test} that silently proves nothing is worse than a red build.
 */
@SpringBootTest
public abstract class DatabaseTest {

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * Empty everything a test creates, leaving the reference data alone.
     *
     * <p>States, cities and body types are seeded once when the context starts; re-seeding 3,285
     * cities per test would dominate the run and prove nothing. CASCADE covers the link tables.
     */
    protected void resetDomainData() {
        jdbc.execute("TRUNCATE vehicles, users, companies, user_x_company, vehicle_x_user, "
                + "vehicle_x_location, company_x_location, vehicle_intake, "
                + "lorry_receipts, consignors "
                + "RESTART IDENTITY CASCADE");
        // The LR counter is a row, not a sequence, so RESTART IDENTITY does not touch it and
        // every test would start numbering from wherever the previous one stopped.
        jdbc.update("UPDATE lr_counters SET next_value = 1");
        // Reference data is not truncated, but it IS mutable — a test that retires a body type
        // would otherwise leave every later test unable to register a vehicle. Cheap, and it
        // makes each test independent of what ran before it.
        jdbc.update("UPDATE body_types SET is_active = TRUE WHERE NOT is_active");
        jdbc.update("UPDATE cities SET is_active = TRUE WHERE NOT is_active");
        // goods_types is seeded by changeset 23, so it is reference data like the two above --
        // truncated it would leave every later test with an empty pick list.
        jdbc.update("UPDATE goods_types SET is_active = TRUE WHERE NOT is_active");
        jdbc.update("UPDATE capacities SET is_active = TRUE WHERE NOT is_active");
    }

    private static final String URL_ENV = "VM_TEST_DATABASE_URL";
    private static final String USER_ENV = "VM_TEST_DATABASE_USER";
    private static final String PASSWORD_ENV = "VM_TEST_DATABASE_PASSWORD";

    private static PostgreSQLContainer<?> container;

    /**
     * A signing key for the tests.
     *
     * <p>The application has no default and refuses to start without one, which is the right
     * behaviour for a deployment and an obstacle for a test. Supplying it here keeps that
     * production guard intact rather than weakening it with a fallback in application.yml.
     */
    protected static final String TEST_JWT_SECRET =
            "test-only-signing-key-at-least-32-bytes-long-0123456789";

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry registry) {
        registry.add("vehicle-management.jwt.secret", () -> TEST_JWT_SECRET);
    }

    /**
     * A throwaway directory for uploaded photos.
     *
     * <p><b>Not the application's.</b> The default is {@code ./uploads}, relative to the Maven
     * working directory — the very directory a locally-running server writes to. A test run
     * once silently replaced a real 56 KB upload there with a 1x1 pixel fixture, and the OCR
     * worker then read an empty image and reported no plate.
     *
     * <p>Storage keys are UUID-based now, so the id collision that caused that particular
     * overwrite is gone. This stays regardless: tests should not be writing into a directory a
     * running server is serving from, whether or not the names happen to clash today.
     */
    @DynamicPropertySource
    static void imageStorage(DynamicPropertyRegistry registry) {
        registry.add("vehicle-management.intake.storage-backend", () -> "local");
        registry.add("vehicle-management.intake.storage-dir",
                () -> System.getProperty("java.io.tmpdir") + "/vm-test-uploads");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv(URL_ENV);
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username",
                    () -> orDefault(System.getenv(USER_ENV), "postgres"));
            registry.add("spring.datasource.password",
                    () -> orDefault(System.getenv(PASSWORD_ENV), "postgres"));
            return;
        }

        Assumptions.assumeTrue(dockerAvailable(),
                "No database for the tests. Set " + URL_ENV + " to a scratch PostgreSQL, "
                + "or start Docker so Testcontainers can provide one. Refusing to fall back to "
                + "H2: it cannot express the constraints these tests exist to prove.");

        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();          // deliberately not stopped: one container per JVM run
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
