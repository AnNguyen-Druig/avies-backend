package com.avies.backend.repository;

import com.avies.backend.dto.request.QuestionSearchRequest;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.QuestionStatus;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

/**
 * Xây dựng truy vấn có điều kiện quyền truy cập bắt buộc trước mọi bộ lọc người dùng.
 */
public final class QuestionSpecifications {
    /**
     * Ngăn khởi tạo lớp chỉ chứa hàm dựng truy vấn.
     */
    private QuestionSpecifications() {
    }

    /**
     * Kết hợp quyền theo môn, quyền sở hữu, trạng thái công khai và bộ lọc danh sách.
     */
    public static Specification<Question> accessibleTo(Long actorId, boolean admin, QuestionSearchRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (!admin) {
                Subquery<Long> assigned = query.subquery(Long.class);
                Root<LecturerSubjectAssignment> assignment = assigned.from(LecturerSubjectAssignment.class);
                assigned.select(assignment.get("id")).where(
                        cb.equal(assignment.get("lecturer").get("id"), actorId),
                        cb.equal(assignment.get("subject").get("id"), root.get("subject").get("id")));
                conditions.add(cb.exists(assigned));
                conditions.add(cb.or(cb.equal(root.get("createdBy").get("id"), actorId),
                        cb.and(cb.equal(root.get("status"), QuestionStatus.APPROVED), cb.isNull(root.get("archivedAt")))));
            }
            conditions.add(filter.isArchived() ? cb.isNotNull(root.get("archivedAt")) : cb.isNull(root.get("archivedAt")));
            if (filter.getSubjectId() != null)
                conditions.add(cb.equal(root.get("subject").get("id"), filter.getSubjectId()));
            if (filter.getCreatedById() != null)
                conditions.add(cb.equal(root.get("createdBy").get("id"), filter.getCreatedById()));
            if (filter.getBloomLevel() != null)
                conditions.add(cb.equal(root.get("bloomLevel"), filter.getBloomLevel()));
            if (filter.getStatus() != null) conditions.add(cb.equal(root.get("status"), filter.getStatus()));
            if (filter.getSourceType() != null)
                conditions.add(cb.equal(root.get("sourceType"), filter.getSourceType()));
            if (filter.getTopic() != null && !filter.getTopic().isBlank()) {
                Subquery<Long> tagged = query.subquery(Long.class);
                Root<Question> taggedQuestion = tagged.from(Question.class);
                SetJoin<Question, String> topic = taggedQuestion.joinSet("topics");
                tagged.select(taggedQuestion.get("id")).where(cb.equal(taggedQuestion.get("id"), root.get("id")),
                        cb.equal(cb.lower(topic), normalize(filter.getTopic())));
                conditions.add(cb.exists(tagged));
            }
            if (filter.getKeyword() != null && !filter.getKeyword().isBlank()) {
                String literal = normalize(filter.getKeyword()).replace("!", "!!").replace("%", "!%").replace("_", "!_");
                conditions.add(cb.like(cb.lower(root.get("content")), "%" + literal + "%", '!'));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /**
     * Chuẩn hóa Unicode và hoa thường giống cách chuẩn hóa dữ liệu đầu vào.
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
