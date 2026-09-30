package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.State;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StateRepository extends JpaRepository<State, Long> {

    Optional<State> findByCode(String code);

    List<State> findAllByOrderByNameAsc();
}
