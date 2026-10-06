package com.avies.backend.service.impl;

import com.avies.backend.dto.request.*;
import com.avies.backend.dto.response.*;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.*;
import com.avies.backend.exception.*;
import com.avies.backend.repository.*;
import com.avies.backend.service.QuestionService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Nghiệp vụ câu hỏi, đảm bảo phân quyền, tính nguyên tử và bảo toàn câu đã dùng trong bài thi.
 */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
@FieldDefaults(level = AccessLevel.PRIVATE,  makeFinal = true)
public class QuestionServiceImpl implements QuestionService {
    /**
     * Kho câu hỏi và truy vấn kiểm tra tham chiếu.
     */
    QuestionRepository questionRepository;

    /**
     * Kho môn học dùng khi tạo câu hỏi.
     */
    SubjectRepository subjectRepository;

    /**
     * Kho tài khoản để kiểm tra quyền hiện tại, không chỉ dựa vào role cũ trong JWT.
     */
    UserRepository userRepository;

    /**
     * Kho phân công giảng viên theo môn học.
     */
    LecturerSubjectAssignmentRepository assignmentRepository;

    /**
     * Kho nhật ký được ghi trong cùng transaction với câu hỏi.
     */
    QuestionAuditLogRepository auditRepository;

    /**
     * Tạo bản nháp thủ công và nhật ký, không cho request quyết định tác giả hoặc trạng thái.
     */
    @Override
    @Transactional
    public QuestionResponse createQuestion(QuestionCreationRequest request) {
        User actor = currentActor();
        requireAssignment(actor, request.getSubjectId());
        Subject subject = subjectRepository.findById(request.getSubjectId())
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Question question = Question.builder()
                .subject(subject).createdBy(actor).updatedBy(actor)
                .content(requiredContent(request.getContent()))
                .referenceAnswer(optionalText(request.getReferenceAnswer()))
                .topics(normalizeTopics(request.getTopics()))
                .bloomLevel(request.getBloomLevel())
                .answerTimeLimitSeconds(request.getAnswerTimeLimitSeconds())
                .sourceType(QuestionSourceType.MANUAL).status(QuestionStatus.DRAFT)
                .isAiGenerated(false).isApproved(false).createdAt(now).updatedAt(now).build();
        questionRepository.saveAndFlush(question);
        audit(question, actor, "CREATED");
        return toResponse(question);
    }

    /**
     * Đọc chi tiết và che giấu tài nguyên ngoài phạm vi quyền bằng lỗi không tìm thấy.
     */
    @Override
    public QuestionResponse getQuestion(Long id) {
        User actor = currentActor();
        Question question = questionRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.QUESTION_NOT_FOUND));
        requireVisible(question, actor);
        return toResponse(question);
    }

    /**
     * Truy vấn dữ liệu và tổng số bản ghi trong cùng phạm vi quyền truy cập.
     */
    @Override
    public PageResponse<QuestionSummaryResponse> searchQuestions(QuestionSearchRequest request) {
        User actor = currentActor();
        String[] ordering = request.getSort().split(",");
        Sort sort = Sort.by(Sort.Direction.fromString(ordering[1]), ordering[0]);
        if (!"id".equals(ordering[0])) sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        Page<Question> page = questionRepository.findAll(
                QuestionSpecifications.accessibleTo(actor.getId(), isAdmin(actor), request),
                PageRequest.of(request.getPage(), request.getSize(), sort));
        return PageResponse.<QuestionSummaryResponse>builder()
                .items(page.getContent().stream().map(this::toSummary).toList())
                .page(page.getNumber()).size(page.getSize()).totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages()).build();
    }

    /**
     * Cập nhật bản nháp; yêu cầu không thay đổi giá trị sẽ không tăng version hoặc tạo nhật ký.
     */
    @Override
    @Transactional
    public QuestionResponse updateQuestion(Long id, QuestionUpdateRequest request) {
        User actor = currentActor();
        Question question = questionRepository.findForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.QUESTION_NOT_FOUND));
        requireEditable(question, actor);
        if (!Objects.equals(question.getVersion(), request.getExpectedVersion())) {
            throw new AppException(ErrorCode.QUESTION_VERSION_CONFLICT);
        }
        requireUnused(question);
        String content = request.isContentPresent() ? requiredContent(request.getContent()) : question.getContent();
        String answer = request.isReferenceAnswerPresent() ? optionalText(request.getReferenceAnswer()) : question.getReferenceAnswer();
        Set<String> topics = request.isTopicsPresent() ? normalizeTopics(request.getTopics()) : question.getTopics();
        BloomLevel bloom = request.isBloomLevelPresent() ? request.getBloomLevel() : question.getBloomLevel();
        Integer seconds = request.isAnswerTimeLimitSecondsPresent() ? request.getAnswerTimeLimitSeconds() : question.getAnswerTimeLimitSeconds();
        if (Objects.equals(content, question.getContent()) && Objects.equals(answer, question.getReferenceAnswer())
                && topics.equals(question.getTopics()) && bloom == question.getBloomLevel()
                && Objects.equals(seconds, question.getAnswerTimeLimitSeconds())) {
            return toResponse(question);
        }
        question.setContent(content);
        question.setReferenceAnswer(answer);
        if (request.isTopicsPresent()) {
            question.getTopics().clear();
            question.getTopics().addAll(topics);
        }
        question.setBloomLevel(bloom);
        question.setAnswerTimeLimitSeconds(seconds);
        question.setUpdatedBy(actor);
        question.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        questionRepository.flush();
        audit(question, actor, "UPDATED");
        return toResponse(question);
    }

    /**
     * Xóa an toàn và lặp lại được; nhật ký không bị xóa theo bản nháp.
     */
    @Override
    @Transactional
    public void deleteQuestion(Long id) {
        User actor = currentActor();
        Optional<Question> existing = questionRepository.findForUpdate(id);
        if (existing.isEmpty()) return;
        Question question = existing.get();
        requireEditable(question, actor);
        requireUnused(question);
        audit(question, actor, "DELETED");
        questionRepository.delete(question);
        questionRepository.flush();
    }

    /**
     * Kiểm tra tài khoản còn tồn tại và hiện vẫn có vai trò được quản lý câu hỏi.
     */
    private User currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User actor = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (actor.getRole() == null || (!isAdmin(actor) && !"LECTURER".equals(actor.getRole().getCode()))) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    /**
     * Xác định quyền quản trị theo dữ liệu tài khoản hiện hành.
     */
    private boolean isAdmin(User actor) {
        return actor.getRole() != null && "ADMIN".equals(actor.getRole().getCode());
    }

    /**
     * Bắt buộc giảng viên có phân công môn học, admin được quản lý toàn bộ.
     */
    private void requireAssignment(User actor, Long subjectId) {
        if (!isAdmin(actor) && !assignmentRepository.existsByLecturerIdAndSubjectId(actor.getId(), subjectId)) {
            throw new AppException(ErrorCode.SUBJECT_NOT_ASSIGNED);
        }
    }

    /**
     * Cho phép xem câu của mình hoặc câu được duyệt đang hoạt động trong môn được phân công.
     */
    private void requireVisible(Question question, User actor) {
        if (isAdmin(actor)) return;
        boolean assigned = assignmentRepository.existsByLecturerIdAndSubjectId(actor.getId(), question.getSubject().getId());
        boolean owner = actor.getId().equals(question.getCreatedBy().getId());
        boolean published = question.getStatus() == QuestionStatus.APPROVED && question.getArchivedAt() == null;
        if (!assigned || (!owner && !published)) throw new AppException(ErrorCode.QUESTION_NOT_FOUND);
    }

    /**
     * Kiểm tra quyền sở hữu và trạng thái; admin cũng không được bỏ qua điều kiện bản nháp.
     */
    private void requireEditable(Question question, User actor) {
        requireVisible(question, actor);
        if (!isAdmin(actor) && !actor.getId().equals(question.getCreatedBy().getId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (question.getStatus() != QuestionStatus.DRAFT || question.getArchivedAt() != null) {
            throw new AppException(ErrorCode.QUESTION_NOT_EDITABLE);
        }
    }

    /**
     * Chặn thay đổi câu đã dùng cho đến khi chức năng snapshot bài thi được triển khai.
     */
    private void requireUnused(Question question) {
        if (questionRepository.countExamReferences(question.getId()) > 0) {
            throw new AppException(ErrorCode.QUESTION_IN_USE);
        }
    }

    /**
     * Chuẩn hóa khoảng trắng ở đầu/cuối nhưng giữ nguyên cấu trúc dòng của nội dung.
     */
    private String requiredContent(String value) {
        String text = optionalText(value);
        if (text == null) throw new AppException(ErrorCode.INVALID_REQUEST, "Nội dung câu hỏi không được để trống.");
        return text;
    }

    /**
     * Chuẩn hóa Unicode; văn bản trống được biểu diễn bằng null.
     */
    private String optionalText(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (normalized.indexOf('\0') >= 0)
            throw new AppException(ErrorCode.INVALID_REQUEST, "Văn bản chứa ký tự không hợp lệ.");
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Loại nhãn trùng không phân biệt hoa thường và giữ cách viết của nhãn xuất hiện đầu tiên.
     */
    private Set<String> normalizeTopics(Collection<String> values) {
        if (values == null) throw new AppException(ErrorCode.INVALID_REQUEST, "Danh sách chủ đề không được null.");
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            String topic = requiredContent(value);
            unique.putIfAbsent(topic.toLowerCase(Locale.ROOT), topic);
        }
        return new LinkedHashSet<>(unique.values());
    }

    /**
     * Ghi nhật ký cùng transaction để không tồn tại sự kiện của thao tác đã rollback.
     */
    private void audit(Question question, User actor, String action) {
        auditRepository.save(QuestionAuditLog.builder().questionId(question.getId()).actor(actor)
                .action(action).occurredAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /**
     * Ánh xạ tường minh nhằm tránh lộ thuộc tính nhạy cảm hoặc vòng tham chiếu entity.
     */
    private QuestionResponse toResponse(Question question) {
        return QuestionResponse.builder().id(question.getId())
                .subjectId(question.getSubject().getId()).subjectName(question.getSubject().getName())
                .content(question.getContent()).referenceAnswer(question.getReferenceAnswer())
                .topics(new TreeSet<>(question.getTopics())).bloomLevel(question.getBloomLevel())
                .answerTimeLimitSeconds(question.getAnswerTimeLimitSeconds()).sourceType(question.getSourceType())
                .status(question.getStatus()).createdById(question.getCreatedBy().getId())
                .createdByName(question.getCreatedBy().getFullName())
                .updatedById(question.getUpdatedBy() == null ? null : question.getUpdatedBy().getId())
                .createdAt(question.getCreatedAt()).updatedAt(question.getUpdatedAt())
                .archivedAt(question.getArchivedAt()).version(question.getVersion())
                .rubricId(question.getRubric() == null ? null : question.getRubric().getId()).build();
    }

    /**
     * Ánh xạ danh sách và cắt nội dung theo code point để không cắt đôi ký tự Unicode.
     */
    private QuestionSummaryResponse toSummary(Question question) {
        String content = question.getContent();
        int length = content.offsetByCodePoints(0, Math.min(200, content.codePointCount(0, content.length())));
        return QuestionSummaryResponse.builder().id(question.getId())
                .subjectId(question.getSubject().getId()).subjectName(question.getSubject().getName())
                .contentPreview(content.substring(0, length)).topics(new TreeSet<>(question.getTopics()))
                .bloomLevel(question.getBloomLevel()).sourceType(question.getSourceType()).status(question.getStatus())
                .createdById(question.getCreatedBy().getId()).createdByName(question.getCreatedBy().getFullName())
                .createdAt(question.getCreatedAt()).archivedAt(question.getArchivedAt())
                .hasRubric(question.getRubric() != null).build();
    }
}
