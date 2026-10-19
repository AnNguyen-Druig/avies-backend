package com.avies.backend.repository;

import com.avies.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * Khóa tài khoản đích đến hết transaction để tuần tự hóa phân công/thu hồi cùng giảng viên.
     */
    @Query(value =
            "SELECT id FROM aives.users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockForSubjectAssignment(@Param("id") Long id);

    /** Dùng cùng khóa user với phân công; ghi phiên thi bị xung đột sẽ trả 409 thay vì chờ ghi đè. */
    @Query(value = "SELECT id FROM aives.users WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
    Optional<Long> tryLockForExamSessionWrite(@Param("id") Long id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
