package com.vehiclemanagement.service;

import com.vehiclemanagement.exception.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Photos on local disk. Development and tests.
 *
 * <p>Not for production behind more than one instance: two API nodes would each hold half the
 * photos, and the OCR worker would find whichever half it shares a filesystem with. Use
 * {@link GcsImageStore} there.
 */
public class LocalImageStore implements ImageStore {

    private final Path root;

    public LocalImageStore(String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(new java.io.ByteArrayInputStream(bytes), target,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(500, "Could not store the photo.");
        }
    }

    @Override
    public byte[] get(String key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            throw new ApiException.NotFound("That photo is no longer available.");
        }
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new ApiException(500, "Could not read the photo.");
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
            // The per-upload folder is empty once its last photo goes; leave no husk behind.
            Path folder = target.getParent();
            if (folder != null && !folder.equals(root) && Files.isDirectory(folder)) {
                try (var left = Files.list(folder)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(folder);
                    }
                }
            }
        } catch (IOException e) {
            throw new ApiException(500, "Could not delete the photo.");
        }
    }

    /**
     * Resolve a key under the storage root, refusing anything that climbs out of it.
     *
     * <p>The guard matters even though keys are minted by {@link #keyFor}: they are also read
     * back out of the database and handed to this method, and the database now has a second
     * writer in another language. A bad row must not be able to reach {@code ../../etc}.
     */
    private Path resolve(String key) {
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new ApiException.BadRequest("Invalid image key.");
        }
        return candidate;
    }
}
