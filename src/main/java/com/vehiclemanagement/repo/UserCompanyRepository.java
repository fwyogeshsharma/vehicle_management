package com.vehiclemanagement.repo;

import com.vehiclemanagement.domain.UserCompany;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserCompanyRepository
        extends JpaRepository<UserCompany, UserCompany.Key> {

    List<UserCompany> findByUserId(Long userId);

    List<UserCompany> findByCompanyId(Long companyId);

    /**
     * End an employment.
     *
     * <p>A native delete rather than {@code deleteById}, so it reaches the database inside this
     * call: fk_vxu_employed cascades the person's assignments on that company's trucks away with
     * it, and the caller has to report what changed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM user_x_company WHERE user_id = :userId AND company_id = :companyId",
           nativeQuery = true)
    int deleteMembership(@Param("userId") Long userId, @Param("companyId") Long companyId);
}
