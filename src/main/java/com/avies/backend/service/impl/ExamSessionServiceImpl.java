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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final UserRepository users;
    private final SubjectRepository subjects;
    private final LecturerSubjectAssignmentRepository assignments;

    /**
     * Refresh sau khi lấy khóa để không kiểm tra bản entity cũ trong persistence context.
     */
    @PersistenceContext
    private EntityManager entityManager;

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
        User actor = currentActor(false);
        ExamSession session = sessions.findForDisplay(id)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        requireVisible(session, actor);
        return toResponse(session);
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
        User actor = currentActor(true);
        users.tryLockForExamSessionWrite(actor.getId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        entityManager.refresh(actor);
        requireRole(actor, true);
        return actor;
    }

    /**
     * Khóa user → môn → phiên; từ chối nếu ownership/môn thay đổi giữa lần đọc và lần khóa.
     */
    private ExamSession ownedForWrite(Long id) {
        User actor = lockWriter();
        ExamSession session = sessions.findForDisplay(id)
                .orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        requireVisible(session, actor);
        Long subjectId = session.getSubject().getId();
        assignedSubject(actor, subjectId);
        sessions.lockForManagement(id).orElseThrow(() -> new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND));
        entityManager.refresh(session);
        requireVisible(session, actor);
        if (!Objects.equals(subjectId, session.getSubject().getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_CONFLICT);
        return session;
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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        User actor = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        requireRole(actor, write);
        return actor;
    }

    /**
     * Cả authority JWT và role database phải cùng cấp quyền; ADMIN chỉ được đọc.
     */
    private void requireRole(User actor, boolean write) {
        String role = actor.getRole() == null ? "" : actor.getRole().getCode();
        boolean allowed = "LECTURER".equals(role) || (!write && "ADMIN".equals(role));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean matchingAuthority = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
        if (!allowed || !matchingAuthority) throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    /**
     * Admin đọc toàn bộ, giảng viên chỉ đọc phiên của mình dù còn hay đã thu hồi môn.
     */
    private void requireVisible(ExamSession session, User actor) {
        if (!isAdmin(actor) && !Objects.equals(session.getLecturer().getId(), actor.getId()))
            throw new AppException(ErrorCode.EXAM_SESSION_NOT_FOUND);
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
