package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.IntakeNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface IntakeNoteRepository extends JpaRepository<IntakeNote, Long> {

    List<IntakeNote> findByIntakeIdOrderByCreatedAtAscIdAsc(Long intakeId);

    List<IntakeNote> findByIntakeIdInOrderByCreatedAtAscIdAsc(Collection<Long> intakeIds);
}
