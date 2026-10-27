package com.avies.backend.service.support;

import com.avies.backend.dto.snapshot.*;
import com.avies.backend.entity.ExamSession;
import com.avies.backend.entity.StudentExamQuestion;
import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.RubricRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Chốt/đọc snapshot nội bộ; caller giữ transaction và khóa phiên trước mọi thao tác ghi. */
@Component
@RequiredArgsConstructor
public class ExamSnapshotSupport {
    /** Chỉ đọc rubric nguồn khi phiên chưa từng chốt. */
    private final RubricRepository rubrics;

    /** Codec riêng cho hợp đồng lưu trữ, không phụ thuộc cấu hình JSON HTTP hoặc dữ liệu người dùng. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Chốt rubric môn dưới khóa; không tái tạo hoặc ghi đè payload đã có. */
    public ExamRubricSnapshot captureRubric(ExamSession session) {
        if (session.getRubricSnapshot() != null || session.getRubricSnapshotAt() != null)
            return readRubric(session);
        var snapshot = currentRubric(session.getSubject().getId(), ErrorCode.EXAM_QUESTION_RUBRIC_INVALID);
        try {
            session.setRubricSnapshot(JSON.writeValueAsString(snapshot));
        } catch (JsonProcessingException exception) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
        session.setRubricSnapshotAt(OffsetDateTime.now(ZoneOffset.UTC));
        return snapshot;
    }

    /** Xác minh rubric dưới khóa khi tạo/đổi cấu hình Exam; không ghi snapshot hoặc sửa dữ liệu môn. */
    public ExamRubricSnapshot requireReadySubjectRubric(Long subjectId) {
        return currentRubric(subjectId, ErrorCode.EXAM_SESSION_RUBRIC_REQUIRED);
    }

    /** Đọc rubric nguồn dưới cùng bộ khóa và dùng cùng quy tắc ở cả Create và Start. */
    private ExamRubricSnapshot currentRubric(Long subjectId, ErrorCode error) {
        List<Long> ids = rubrics.lockForSubject(subjectId);
        if (ids.size() != 1) throw new AppException(error);
        rubrics.lockCriteriaForSelection(ids);
        var rows = rubrics.readSnapshotRows(subjectId);
        if (rows.isEmpty()) throw new AppException(error);
        var first = rows.getFirst();
        if (rows.stream().anyMatch(row -> !Objects.equals(ids.getFirst(), row.getRubricId())
                || !Objects.equals(subjectId, row.getSubjectId())))
            throw new AppException(error);
        var criteria = rows.stream().map(row -> new ExamRubricSnapshot.Criterion(row.getCriterionId(),
                row.getCriterionText(), row.getPointWeight(), row.getMandatory(), row.getDisplayOrder())).toList();
        var snapshot = new ExamRubricSnapshot(1, first.getRubricId(), subjectId, first.getTitle(), first.getDescription(),
                first.getUpdatedAt() == null ? null : first.getUpdatedAt().toString(), criteria);
        requireRubric(snapshot, subjectId, error);
        return snapshot;
    }

    /** Đọc dữ liệu lịch sử; không fallback về rubric môn dù nguồn đã bị xóa hoặc sửa. */
    public ExamRubricSnapshot readRubric(ExamSession session) {
        if (session.getRubricSnapshot() == null || session.getRubricSnapshotAt() == null)
            throw new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        try {
            var snapshot = JSON.readValue(session.getRubricSnapshot(), ExamRubricSnapshot.class);
            requireRubric(snapshot, session.getSubject().getId(), ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        }
    }

    /** Kiểm tra cả cấu trúc và thang điểm 10; không làm tròn để hợp thức hóa dữ liệu sai. */
    private void requireRubric(ExamRubricSnapshot snapshot, Long subjectId, ErrorCode error) {
        if (snapshot == null || snapshot.schemaVersion() != 1 || snapshot.sourceRubricId() == null
                || snapshot.sourceRubricId() < 1 || !Objects.equals(subjectId, snapshot.subjectId())
                || snapshot.criteria() == null || snapshot.criteria().isEmpty()) throw new AppException(error);
        Set<Long> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var criterion : snapshot.criteria()) {
            BigDecimal score = criterion.maxScore();
            if (criterion.sourceCriterionId() == null || criterion.sourceCriterionId() < 1
                    || !ids.add(criterion.sourceCriterionId()) || !usableText(criterion.text())
                    || score == null || score.signum() <= 0 || score.compareTo(BigDecimal.TEN) > 0
                    || score.stripTrailingZeros().scale() > 2 || criterion.mandatory() == null
                    || criterion.displayOrder() == null || criterion.displayOrder() < 1
                    || !orders.add(criterion.displayOrder())) throw new AppException(error);
            total = total.add(score);
        }
        if (total.compareTo(BigDecimal.TEN) != 0) throw new AppException(error);
    }

    /** Đọc câu đã chốt; null đáp án hoặc giới hạn vẫn hợp lệ, thiếu nội dung/Bloom/version thì báo legacy. */
    public ExamQuestionSnapshot readQuestion(StudentExamQuestion row) {
        if (row.getSnapshotCapturedAt() == null || !usableText(row.getSnapshotContent())
                || row.getSnapshotQuestionVersion() == null || row.getSnapshotQuestionVersion() < 0
                || row.getSnapshotBloomLevel() == null || (row.getSnapshotAnswerTimeLimitSeconds() != null
                && row.getSnapshotAnswerTimeLimitSeconds() <= 0)) throw new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE);
        try { BloomLevel.valueOf(row.getSnapshotBloomLevel()); }
        catch (IllegalArgumentException exception) { throw new AppException(ErrorCode.EXAM_SNAPSHOT_UNAVAILABLE); }
        return new ExamQuestionSnapshot(row.getId(), row.getQuestion().getId(), row.getQuestionOrder(),
                row.getSnapshotQuestionVersion(), row.getSnapshotContent(), row.getSnapshotReferenceAnswer(),
                row.getSnapshotBloomLevel(), row.getSnapshotAnswerTimeLimitSeconds(), row.getSnapshotCapturedAt());
    }

    /** Kiểm tra văn bản dùng được mà không thay đổi bản gốc. */
    private boolean usableText(String text) { return text != null && !text.isBlank() && text.indexOf('\0') < 0; }
}
