package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.UploadLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UploadLinkRepository extends JpaRepository<UploadLink, Long> {

    Optional<UploadLink> findByTokenHash(String tokenHash);

    @Modifying
    @Query(value = "UPDATE upload_links SET last_used_at = now() WHERE user_id = :userId",
            nativeQuery = true)
    void touch(@Param("userId") long userId);
}
