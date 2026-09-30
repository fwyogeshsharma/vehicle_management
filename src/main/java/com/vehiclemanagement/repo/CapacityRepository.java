package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.Capacity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CapacityRepository extends JpaRepository<Capacity, Long> {

    /** Smallest first. By tonnage, because alphabetically "9 Ton" sorts between 16 and 25. */
    List<Capacity> findByActiveTrueOrderByTonsAsc();

    List<Capacity> findAllByOrderByTonsAsc();

    java.util.Optional<Capacity> findByLabelIgnoreCase(String label);
}
