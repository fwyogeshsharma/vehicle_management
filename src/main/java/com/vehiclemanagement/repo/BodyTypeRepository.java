package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.BodyType;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BodyTypeRepository extends JpaRepository<BodyType, Long> {

    Optional<BodyType> findByNameKey(String nameKey);

    List<BodyType> findByActiveTrueOrderByNameAsc();

    List<BodyType> findAllByOrderByNameAsc();
}
