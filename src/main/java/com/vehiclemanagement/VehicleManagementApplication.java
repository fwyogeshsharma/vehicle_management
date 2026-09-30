package com.vehiclemanagement;

import com.vehiclemanagement.config.VehicleManagementProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Vehicle management: companies, vehicles, the people attached to them, and where they run.
 *
 * <p>Running it applies the Liquibase changelog, validates that the entities still match, seeds
 * reference data, creates the bootstrap administrator if one is configured and none exists, and
 * then serves the REST API on {@code VM_PORT} (8080 by default). It no longer exits after
 * seeding — that was true only while there was no web layer.
 *
 * <p>It refuses to start without {@code VM_JWT_SECRET}. That is deliberate: see SecurityConfig.
 */
@SpringBootApplication
@EnableConfigurationProperties(VehicleManagementProperties.class)
public class VehicleManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(VehicleManagementApplication.class, args);
    }
}
