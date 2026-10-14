package com.avies.backend.repository;

import com.avies.backend.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {
    /** Giữ các môn tồn tại đến khi transaction kết thúc; khóa theo ID để giảm nguy cơ deadlock. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Subject s where s.id in :ids order by s.id")
    List<Subject> findAllForAssignment(@Param("ids") Collection<Long> ids);

    Optional<Subject> findByCode(String code);
    boolean existsByCode(String code);
}
