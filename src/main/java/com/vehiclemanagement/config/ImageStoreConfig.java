package com.vehiclemanagement.config;

import com.google.cloud.storage.StorageOptions;
import com.vehiclemanagement.service.GcsImageStore;
import com.vehiclemanagement.service.ImageStore;
import com.vehiclemanagement.service.LocalImageStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the photo backend from configuration, exactly as FreightDesk's {@code get_storage()}
 * does from {@code IMAGE_STORAGE_BACKEND}.
 *
 * <p>The GCS client is built <b>only</b> in the {@code gcs} branch. {@code
 * StorageOptions.getDefaultInstance()} goes looking for Application Default Credentials and
 * throws when it finds none, so constructing it eagerly would make local development and the
 * test suite require a Google account to start.
 *
 * <p>An unrecognised value is a startup failure rather than a silent fallback to local disk.
 * Falling back would mean a production deploy with a typo in one environment variable quietly
 * writing every photo to a container filesystem that vanishes on the next restart — and nobody
 * noticing until a CSR opened a report and found no picture.
 */
@Configuration
public class ImageStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ImageStoreConfig.class);

    @Bean
    public ImageStore imageStore(VehicleManagementProperties properties) {
        VehicleManagementProperties.Intake intake = properties.getIntake();
        String backend = intake.getStorageBackend() == null
                ? "local" : intake.getStorageBackend().strip().toLowerCase();

        return switch (backend) {
            case "local" -> {
                log.info("Photo storage: local disk at {}", intake.getStorageDir());
                yield new LocalImageStore(intake.getStorageDir());
            }
            case "gcs" -> {
                log.info("Photo storage: GCS bucket {} (prefix {})",
                        intake.getGcsBucket(), intake.getGcsPrefix());
                yield new GcsImageStore(StorageOptions.getDefaultInstance().getService(),
                        intake.getGcsBucket(), intake.getGcsPrefix());
            }
            default -> throw new IllegalStateException(
                    "Unknown vehicle-management.intake.storage-backend: '" + backend
                            + "'. Expected 'local' or 'gcs'.");
        };
    }
}
