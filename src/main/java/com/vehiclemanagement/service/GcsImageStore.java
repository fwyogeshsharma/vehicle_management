package com.vehiclemanagement.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.vehiclemanagement.exception.ApiException;

/**
 * Photos in a Google Cloud Storage bucket. Production.
 *
 * <p>The same bucket the Python OCR worker reads from, by the same keys — see
 * {@code vehicleManagementOcr/ocr/storage.py}. Both sides authenticate with Application Default
 * Credentials, so on GCP neither carries a key file.
 *
 * <p><b>Retention is the bucket's business, not this class's.</b> There is no delete and no
 * sweep here, exactly as in FreightDesk's {@code GCSStorage}: an object's lifetime is a property
 * of the bucket's lifecycle rule, set with {@code gcloud storage buckets update
 * --lifecycle-file=...}. Code that also thought it owned expiry would be a second, disagreeing
 * answer to the same question — and the one that deletes a photo a telecaller still needs.
 */
public class GcsImageStore implements ImageStore {

    private final Storage storage;
    private final String bucket;
    private final String prefix;

    public GcsImageStore(Storage storage, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "vehicle-management.intake.gcs-bucket must be set when the storage backend "
                            + "is 'gcs'");
        }
        this.storage = storage;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "");
    }

    private BlobId blobId(String key) {
        return BlobId.of(bucket, prefix.isEmpty() ? key : prefix + "/" + key);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        BlobInfo info = BlobInfo.newBuilder(blobId(key))
                .setContentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        try {
            storage.create(info, bytes);
        } catch (StorageException e) {
            // The message can name the bucket and the service account; that belongs in the log,
            // not in a response to a field executive's phone.
            throw new ApiException(500, "Could not store the photo.");
        }
    }

    @Override
    public byte[] get(String key) {
        Blob blob;
        try {
            blob = storage.get(blobId(key));
        } catch (StorageException e) {
            throw new ApiException(500, "Could not read the photo.");
        }
        if (blob == null || !blob.exists()) {
            throw new ApiException.NotFound("That photo is no longer available.");
        }
        return blob.getContent();
    }

    @Override
    public boolean exists(String key) {
        try {
            Blob blob = storage.get(blobId(key));
            return blob != null && blob.exists();
        } catch (StorageException e) {
            return false;
        }
    }
}
