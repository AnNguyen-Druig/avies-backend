package com.avies.backend.service.impl;

import com.avies.backend.dto.snapshot.ExamGradingContext;
import com.avies.backend.exception.*;
import com.avies.backend.repository.StudentExamRepository;
import com.avies.backend.repository.StudentExamQuestionRepository;
import com.avies.backend.service.ExamSnapshotService;
import com.avies.backend.service.support.ExamSnapshotSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/** Đọc bản chụp lịch sử, không gọi AI hoặc tiết lộ đáp án qua các controller hiện có. */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExamSnapshotServiceImpl implements ExamSnapshotService {
    /** Nguồn kiểm tra lượt thi và phiên sở hữu. */
    private final StudentExamRepository exams;
    /** Câu và snapshot theo thứ tự đã gán. */
    private final StudentExamQuestionRepository questions;
    /** Parser/validator dùng chung với lúc chọn câu. */
    private final ExamSnapshotSupport snapshots;

    /** Chặn bộ thiếu, lệch thứ tự hoặc legacy; tuyệt đối không backfill khi đọc. */
    @Override
    public ExamGradingContext loadForGrading(Long studentExamId) {
        var exam = exams.findById(studentExamId).orElseThrow(() -> new AppException(ErrorCode.STUDENT_EXAM_NOT_FOUND));
        var session = exam.getSession();
        var rubric = snapshots.readRubric(session);
        var rows = questions.findByStudentExamIdOrderByQuestionOrderAscIdAsc(studentExamId);
        if (session.getMaxMainQuestions() == null || rows.size() != session.getMaxMainQuestions()
                || rows.isEmpty() || rows.stream().map(row -> row.getQuestion().getId()).distinct().count() != rows.size())
            throw new AppException(ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        for (int i = 0; i < rows.size(); i++) {
            if (!Objects.equals(rows.get(i).getQuestionOrder(), i + 1))
                throw new AppException(ErrorCode.EXAM_QUESTION_SET_INCONSISTENT);
        }
        return new ExamGradingContext(studentExamId, session.getId(), rubric, session.getRubricSnapshotAt(),
                rows.stream().map(snapshots::readQuestion).toList());
    }
}
