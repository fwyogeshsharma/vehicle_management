package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.GoodsType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoodsTypeRepository extends JpaRepository<GoodsType, Long> {

    Optional<GoodsType> findByNameKey(String nameKey);

    List<GoodsType> findByActiveTrueOrderByNameAsc();

    List<GoodsType> findAllByOrderByNameAsc();
}
