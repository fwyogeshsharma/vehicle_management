package com.vehiclemanagement.bootstrap;

import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.domain.State;
import com.vehiclemanagement.repo.BodyTypeRepository;
import com.vehiclemanagement.repo.CityRepository;
import com.vehiclemanagement.repo.StateRepository;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.GeoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads states, cities and the default body types at start-up, idempotently.
 *
 * <p>Reference data is seeded here rather than in a Liquibase changeset on purpose. A changeset
 * is checksummed and recorded once, so extending the city list would mean a new changeset every
 * time (editing the old one fails validation); this just needs a restart. The cost is two queries per state on every boot
 * once everything is present, which is why the existing keys are prefetched in one query rather
 * than probed per city.
 *
 * <p>Edit {@code data/india_states_cities.txt} to add a town — the format is
 * {@code CODE|State name|City;City;City}, and lines starting with # are ignored.
 */
@Component
@Order(0)
public class MasterDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterDataSeeder.class);
    private static final String DATA_FILE = "data/india_states_cities.txt";

    /**
     * Seeded once, then owned by whoever runs the system. Widened from the TTS list, which
     * called these "vehicle types" while listing body shapes.
     */
    private static final List<String> DEFAULT_BODY_TYPES = List.of(
            "Open body", "Half body", "Full body",
            "Container 20 ft", "Container 32 ft", "Container 40 ft",
            "Trailer", "Low bed trailer", "Tanker", "Tipper", "Flatbed", "Bulker",
            "Refrigerated", "Car carrier", "LCV");

    private final VehicleManagementProperties properties;
    private final GeoService geo;
    private final BodyTypeService bodyTypes;
    private final StateRepository states;
    private final CityRepository cities;
    private final BodyTypeRepository bodyTypeRepo;

    public MasterDataSeeder(VehicleManagementProperties properties, GeoService geo,
                            BodyTypeService bodyTypes, StateRepository states,
                            CityRepository cities, BodyTypeRepository bodyTypeRepo) {
        this.properties = properties;
        this.geo = geo;
        this.bodyTypes = bodyTypes;
        this.states = states;
        this.cities = cities;
        this.bodyTypeRepo = bodyTypeRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isSeedReferenceData()) {
            log.info("Reference-data seeding is off (vehicle-management.seed-reference-data).");
            return;
        }
        int newStates = 0;
        int newCities = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DATA_FILE).getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) {
                    log.warn("Skipping malformed line in {}: {}", DATA_FILE, line);
                    continue;
                }
                String code = parts[0].trim();
                String stateName = parts[1].trim();

                boolean existed = states.findByCode(code).isPresent();
                State state = geo.ensureState(code, stateName);
                if (!existed) {
                    newStates++;
                }

                // One query for what this state already has, rather than one per city.
                Set<String> present = new HashSet<>(cities.findNameKeysByStateId(state.getId()));
                for (String cityName : parts[2].split(";")) {
                    String clean = cityName.trim();
                    if (clean.isEmpty() || present.contains(clean.toLowerCase())) {
                        continue;
                    }
                    geo.ensureCity(state, clean);
                    present.add(clean.toLowerCase());
                    newCities++;
                }
            }
        }

        int newBodyTypes = 0;
        for (String name : DEFAULT_BODY_TYPES) {
            if (bodyTypeRepo.findByNameKey(name.toLowerCase()).isEmpty()) {
                bodyTypes.ensure(name);
                newBodyTypes++;
            }
        }

        if (newStates + newCities + newBodyTypes == 0) {
            log.info("Reference data already present ({} states, {} cities).",
                    states.count(), cities.count());
        } else {
            log.info("Seeded {} state(s), {} city/cities, {} body type(s). Totals: {} states, {} cities.",
                    newStates, newCities, newBodyTypes, states.count(), cities.count());
        }
    }
}
