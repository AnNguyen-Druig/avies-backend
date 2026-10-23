package com.avies.backend.service.impl;

import com.avies.backend.dto.request.RubricCreationRequest;
import com.avies.backend.dto.request.RubricCriterionRequest;
import com.avies.backend.dto.request.RubricUpdateRequest;
import com.avies.backend.dto.response.RubricCriterionResponse;
import com.avies.backend.dto.response.RubricResponse;
import com.avies.backend.entity.Rubric;
import com.avies.backend.entity.RubricCriterion;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.RubricRepository;
import com.avies.backend.service.RubricService;
import com.avies.backend.service.support.SubjectAccessHelper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RubricServiceImpl implements RubricService {

    RubricRepository rubricRepository;
    SubjectAccessHelper subjectAccessHelper;

    private static final BigDecimal EXPECTED_TOTAL_WEIGHT = new BigDecimal("10.00");

    @Override
    @Transactional
    public RubricResponse createRubric(RubricCreationRequest request) {
        User actor = subjectAccessHelper.getCurrentActor();
        Subject subject = subjectAccessHelper.requireAssignmentAndGetSubject(actor, request.getSubjectId());

        if (rubricRepository.existsBySubjectId(subject.getId())) {
            throw new AppException(ErrorCode.RUBRIC_EXISTED);
        }

        validateCriteria(request.getCriteria());

        String title = StringUtils.hasText(request.getTitle())
                ? request.getTitle().trim()
                : "Rubric đánh giá môn " + subject.getName();

        Rubric rubric = Rubric.builder()
                .subject(subject)
                .title(title)
                .description(request.getDescription())
                .criteria(new ArrayList<>())
                .build();

        int defaultOrder = 1;
        for (RubricCriterionRequest cReq : request.getCriteria()) {
            RubricCriterion criterion = RubricCriterion.builder()
                    .rubric(rubric)
                    .criterionText(cReq.getCriterionText().trim())
                    .pointWeight(cReq.getPointWeight())
                    .isMandatory(Boolean.TRUE.equals(cReq.getIsMandatory()))
                    .displayOrder(cReq.getDisplayOrder() != null ? cReq.getDisplayOrder() : defaultOrder++)
                    .build();
            rubric.getCriteria().add(criterion);
        }

        Rubric saved = rubricRepository.save(rubric);
        log.info("Da tao Rubric id={} cho subjectId={} boi actor={}", saved.getId(), subject.getId(), actor.getUsername());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public RubricResponse getRubricById(Long id) {
        Rubric rubric = rubricRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RUBRIC_NOT_FOUND));

        User actor = subjectAccessHelper.getCurrentActor();
        subjectAccessHelper.requireAssignment(actor, rubric.getSubject().getId());

        return toResponse(rubric);
    }

    @Override
    @Transactional(readOnly = true)
    public RubricResponse getRubricBySubjectId(Long subjectId) {
        User actor = subjectAccessHelper.getCurrentActor();
        subjectAccessHelper.requireAssignmentAndGetSubject(actor, subjectId);

        Rubric rubric = rubricRepository.findBySubjectId(subjectId)
                .orElseThrow(() -> new AppException(ErrorCode.RUBRIC_NOT_FOUND));

        return toResponse(rubric);
    }

    @Override
    @Transactional
    public RubricResponse updateRubric(Long id, RubricUpdateRequest request) {
        Rubric rubric = rubricRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RUBRIC_NOT_FOUND));

        User actor = subjectAccessHelper.getCurrentActor();
        subjectAccessHelper.requireAssignment(actor, rubric.getSubject().getId());

        if (StringUtils.hasText(request.getTitle())) {
            rubric.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            rubric.setDescription(request.getDescription().trim());
        }
        rubric.setUpdatedAt(OffsetDateTime.now());

        // Nếu client có truyền criteria thì mới validate và cập nhật lại danh sách tiêu chí
        if (request.getCriteria() != null) {
            validateCriteria(request.getCriteria());

            rubric.getCriteria().clear();
            rubricRepository.flush();

            int defaultOrder = 1;
            for (RubricCriterionRequest cReq : request.getCriteria()) {
                RubricCriterion criterion = RubricCriterion.builder()
                        .rubric(rubric)
                        .criterionText(cReq.getCriterionText().trim())
                        .pointWeight(cReq.getPointWeight())
                        .isMandatory(Boolean.TRUE.equals(cReq.getIsMandatory()))
                        .displayOrder(cReq.getDisplayOrder() != null ? cReq.getDisplayOrder() : defaultOrder++)
                        .build();
                rubric.getCriteria().add(criterion);
            }
        }

        Rubric saved = rubricRepository.save(rubric);
        log.info("Da cap nhat Rubric id={} boi actor={}", saved.getId(), actor.getUsername());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteRubric(Long id) {
        Rubric rubric = rubricRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RUBRIC_NOT_FOUND));

        User actor = subjectAccessHelper.getCurrentActor();
        subjectAccessHelper.requireAssignment(actor, rubric.getSubject().getId());

        rubricRepository.delete(rubric);
        log.info("Da xoa Rubric id={} cua subjectId={} boi actor={}", id, rubric.getSubject().getId(), actor.getUsername());
    }

    private void validateCriteria(List<RubricCriterionRequest> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            throw new AppException(ErrorCode.RUBRIC_CRITERIA_EMPTY);
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (RubricCriterionRequest c : criteria) {
            if (c.getPointWeight() == null || c.getPointWeight().compareTo(BigDecimal.ZERO) <= 0) {
                throw new AppException(ErrorCode.RUBRIC_CRITERIA_INVALID, "Điểm tiêu chí phải lớn hơn 0");
            }
            sum = sum.add(c.getPointWeight());
        }

        if (sum.compareTo(EXPECTED_TOTAL_WEIGHT) != 0) {
            throw new AppException(ErrorCode.RUBRIC_CRITERIA_INVALID,
                    "Tổng điểm các tiêu chí rubric phải bằng 10.00 (hiện tại: " + sum + ")");
        }
    }

    private RubricResponse toResponse(Rubric rubric) {
        List<RubricCriterionResponse> criteriaResponses = (rubric.getCriteria() == null)
                ? Collections.emptyList()
                : rubric.getCriteria().stream()
                .sorted(Comparator.comparing(RubricCriterion::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> RubricCriterionResponse.builder()
                        .id(c.getId())
                        .criterionText(c.getCriterionText())
                        .pointWeight(c.getPointWeight())
                        .isMandatory(c.getIsMandatory())
                        .displayOrder(c.getDisplayOrder())
                        .build())
                .toList();

        return RubricResponse.builder()
                .id(rubric.getId())
                .subjectId(rubric.getSubject() != null ? rubric.getSubject().getId() : null)
                .subjectCode(rubric.getSubject() != null ? rubric.getSubject().getCode() : null)
                .subjectName(rubric.getSubject() != null ? rubric.getSubject().getName() : null)
                .title(rubric.getTitle())
                .description(rubric.getDescription())
                .criteria(criteriaResponses)
                .createdAt(rubric.getCreatedAt())
                .updatedAt(rubric.getUpdatedAt())
                .build();
    }
}
