package com.avies.backend.service.impl;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.ExamSession;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.entity.enums.ExamSessionStatus;
import com.avies.backend.entity.enums.StudentExamStatus;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.*;
import com.avies.backend.service.ExamSessionService;
import com.avies.backend.service.support.ExamAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * CRUD phiên thi với quyền hiện hành; khóa ghi theo user → subject → session, không tạo bài thi.
 */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExamSessionServiceImpl implements ExamSessionService {

    private final ExamSessionRepository sessions;
    private final StudentExamRepository studentExams;
    private final SubjectRepository subjects;
    private final LecturerSubjectAssignmentRepository assignments;

    /** Chính sách quyền và khóa dùng chung với batch gán/thu hồi sinh viên. */
    private final ExamAccessPolicy access;

    /**
     * Tạo phiên UPCOMING trong môn hiện được phân công, chưa cần câu đã duyệt hoặc sinh viên.
     */
    @Override
    @Transactional(timeout = 10)
    public ExamSessionResponse create(ExamSessionCreationRequest request) {
        User actor = lockWriter();
        Subject subject = assignedSubject(actor, request.getSubjectId());
        ExamSession session = ExamSession.builder().title(normalizeTitle(request.getTitle()))
                .lecturer(actor).subject(subject).status(ExamSessionStatus.UPCOMING)
                .scheduledStartAt(request.getScheduledStartAt()).scheduledEndAt(request.getScheduledEndAt())
                .slotDurationMinutes(request.getSlotDurationMinutes()).maxMainQuestions(request.getMaxMainQuestions())
                .maxFollowUps(request.getMaxFollowUps()).build();
        validateFinalState(session);
        sessions.saveAndFlush(session);
        return toResponse(session);
    }

    /**
     * Quyền đọc không phụ thuộc phân công còn hiệu lực; lọc sở hữu ngay trong query/count.
     */
    @Override
    public PageResponse<ExamSessionResponse> search(ExamSessionSearchRequest request) {
        User actor = currentActor(false);
        var page = sessions.searchAccessible(isAdmin(actor) ? null : actor.getId(), request.getSubjectId(),
                request.getStatus(), PageRequest.of(request.getPage(), request.getSize()));
        return PageResponse.<ExamSessionResponse>builder().items(page.map(this::toResponse).getContent())
                .page(page.getNumber()).size(page.getSize()).totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages()).build();
    }

    /**
     * Che phiên của người khác bằng cùng lỗi 404 như ID không tồn tại.
     */
    @Override
    public ExamSessionResponse get(Long id) {
        return toResponse(access.readableSession(id));
    }

    /**
     * Gộp PATCH trên entity vừa refresh dưới khóa; trường bỏ qua không ghi đè dữ liệu mới nhất.
     */
    @Override
    @Transactional(timeout = 10)
    public ExamSessionResponse update(Long id, ExamSessionUpdateRequest request) {
        ExamSession session = ownedForWrite(id);
        requireUpcoming(session);
        if (studentExams.hasStartedExam(id, StudentExamStatus.ASSIGNED))
            throw new AppException(ErrorCode.EXAM_SESSION_IN_USE);
        if (studentExams.existsBySessionId(id) && scheduleChanges(session, request))
            throw new AppException(ErrorCode.EXAM_SESSION_SCHEDULE_LOCKED);
        if (request.isTitlePresent()) session.setTitle(normalizeTitle(request.getTitle()));
        if (request.isScheduledStartAtPresent()) session.setScheduledStartAt(request.getScheduledStartAt());
        if (request.isScheduledEndAtPresent()) session.setScheduledEndAt(request.getScheduledEndAt());
        if (request.isSlotDurationMinutesPresent()) session.setSlotDurationMinutes(request.getSlotDurationMinutes());
        if (request.isMaxMainQuestionsPresent()) session.setMaxMainQuestions(request.getMaxMainQuestions());
        if (request.isMaxFollowUpsPresent()) session.setMaxFollowUps(request.getMaxFollowUps());
        validateFinalState(session);
        sessions.flush();
        return toResponse(session);
    }

    /**
     * Xóa chỉ sau khi khóa parent đủ mạnh và kiểm tra không có StudentExam; không xóa con thủ công.
     */
    @Override
    @Transactional(timeout = 10)
    public void delete(Long id) {
        ExamSession session = ownedForWrite(id);
        requireUpcoming(session);
        if (studentExams.existsBySessionId(id)) throw new AppException(ErrorCode.EXAM_SESSION_IN_USE);
        sessions.delete(session);
        sessions.flush();
    }

    /**
     * Khóa user cùng hàng với phân công; kiểm tra lại role sau chờ để token cũ không vượt quyền.
     */
    private User lockWriter() {
        return access.lockWriter();
    }

    /**
     * Khóa user → môn → phiên; từ chối nếu ownership/môn thay đổi giữa lần đọc và lần khóa.
     */
    private ExamSession ownedForWrite(Long id) {
        return access.ownedForWrite(id);
    }

    /**
     * Giữ môn tồn tại rồi kiểm tra phân công dưới khóa user chung với thao tác thu hồi.
     */
    private Subject assignedSubject(User actor, Long subjectId) {
        var found = subjects.findAllForAssignment(List.of(subjectId));
        if (found.isEmpty()) throw new AppException(ErrorCode.SUBJECT_NOT_EXISTED);
        if (!assignments.existsByLecturerIdAndSubjectId(actor.getId(), subjectId))
            throw new AppException(ErrorCode.SUBJECT_NOT_ASSIGNED);
        return found.getFirst();
    }

    /**
     * Danh tính lấy từ authentication, vai trò lấy từ user hiện hành trong database.
     */
    private User currentActor(boolean write) {
        return write ? access.currentUser("LECTURER") : access.currentUser("LECTURER", "ADMIN");
    }

    /** Timestamp cùng instant được xem là không đổi dù cách biểu diễn offset khác nhau. */
    private boolean scheduleChanges(ExamSession session, ExamSessionUpdateRequest request) {
        return (request.isScheduledStartAtPresent() && !sameInstant(session.getScheduledStartAt(), request.getScheduledStartAt()))
                || (request.isScheduledEndAtPresent() && !sameInstant(session.getScheduledEndAt(), request.getScheduledEndAt()))
                || (request.isSlotDurationMinutesPresent() && !Objects.equals(session.getSlotDurationMinutes(), request.getSlotDurationMinutes()));
    }

    /** So sánh nullable để không tự lấp lịch legacy khi đã có sinh viên. */
    private boolean sameInstant(OffsetDateTime first, OffsetDateTime second) {
        return first == null ? second == null : second != null && first.isEqual(second);
    }

    /**
     * Chỉ dùng role hiện hành, không tin cờ do client gửi.
     */
    private boolean isAdmin(User actor) {
        return actor.getRole() != null && "ADMIN".equals(actor.getRole().getCode());
    }

    /**
     * Chặn sửa/xóa phiên đang thi hoặc đã kết thúc.
     */
    private void requireUpcoming(ExamSession session) {
        if (session.getStatus() != ExamSessionStatus.UPCOMING)
            throw new AppException(ErrorCode.EXAM_SESSION_NOT_EDITABLE);
    }

    /**
     * Chuẩn hóa tiêu đề Unicode và chặn ký tự NUL trước khi gửi PostgreSQL.
     */
    private String normalizeTitle(String value) {
        if (value == null) throw new AppException(ErrorCode.INVALID_REQUEST);
        String title = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (title.isBlank() || title.length() > 255 || title.indexOf('\0') >= 0)
            throw new AppException(ErrorCode.INVALID_REQUEST);
        return title;
    }

    /**
     * Kiểm tra toàn bộ trạng thái sau gộp PATCH bằng thời điểm thực, không so giờ địa phương.
     */
    private void validateFinalState(ExamSession session) {
        OffsetDateTime start = session.getScheduledStartAt();
        OffsetDateTime end = session.getScheduledEndAt();
        if (start == null || end == null || !end.isAfter(start) || !start.isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                || session.getSlotDurationMinutes() == null || session.getSlotDurationMinutes() <= 0
                || session.getMaxMainQuestions() == null || session.getMaxMainQuestions() <= 0
                || session.getMaxFollowUps() == null || session.getMaxFollowUps() < 0)
            throw new AppException(ErrorCode.EXAM_SESSION_INVALID_SCHEDULE);
    }

    /**
     * Mapping tường minh trong transaction; không serialize entity hoặc tải bài thi/điểm sinh viên.
     */
    private ExamSessionResponse toResponse(ExamSession session) {
        return ExamSessionResponse.builder().id(session.getId()).title(session.getTitle())
                .lecturerId(session.getLecturer().getId()).subjectId(session.getSubject().getId())
                .subjectCode(session.getSubject().getCode()).subjectName(session.getSubject().getName())
                .status(session.getStatus()).scheduledStartAt(session.getScheduledStartAt())
                .scheduledEndAt(session.getScheduledEndAt()).slotDurationMinutes(session.getSlotDurationMinutes())
                .maxMainQuestions(session.getMaxMainQuestions()).maxFollowUps(session.getMaxFollowUps())
                .createdAt(session.getCreatedAt()).build();
    }
}
