package com.avies.backend.service.impl;

import com.avies.backend.dto.request.SubjectCreationRequest;
import com.avies.backend.dto.request.SubjectUpdateRequest;
import com.avies.backend.dto.response.SubjectResponse;
import com.avies.backend.entity.Subject;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.SubjectRepository;
import com.avies.backend.service.SubjectService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SubjectServiceImpl implements SubjectService {

    SubjectRepository subjectRepository;

    @Transactional
    public SubjectResponse createSubject(SubjectCreationRequest request) {
        String normalizedCode = request.getCode().trim().toUpperCase();

        if (subjectRepository.findByCode(normalizedCode).isPresent()) {
            throw new AppException(ErrorCode.SUBJECT_EXISTED);
        }

        Subject subject = Subject.builder()
                .code(normalizedCode)
                .name(request.getName().trim())
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .build();

        subject = subjectRepository.save(subject);
        log.info("Tạo mới môn học thành công: id={}, code={}", subject.getId(), subject.getCode());

        return toSubjectResponse(subject);
    }

    @Transactional(readOnly = true)
    public List<SubjectResponse> getAllSubjects() {
        return subjectRepository.findAll().stream()
                .map(this::toSubjectResponse)
                .toList();
    }

    @Transactional
    public SubjectResponse updateSubject(Long id, SubjectUpdateRequest request) {
        Subject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));

        if (request.getCode() != null && !request.getCode().isBlank()) {
            String newCode = request.getCode().trim().toUpperCase();
            if (!subject.getCode().equals(newCode) && subjectRepository.existsByCode(newCode)) {
                throw new AppException(ErrorCode.SUBJECT_EXISTED);
            }
            subject.setCode(newCode);
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            subject.setName(request.getName().trim());
        }

        if (request.getDescription() != null) {
            subject.setDescription(request.getDescription());
        }

        subject = subjectRepository.save(subject);
        return toSubjectResponse(subject);
    }

    @Transactional
    public void deleteSubject(Long id) {
        if (!subjectRepository.existsById(id)) {
            throw new AppException(ErrorCode.SUBJECT_NOT_EXISTED);
        }
        // Chú ý: Cần xử lý cẩn thận nếu môn học đã có tài liệu (LearningMaterial) tham chiếu tới
        subjectRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public SubjectResponse getSubjectById(Long id) {
        Subject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SUBJECT_NOT_EXISTED));
        return toSubjectResponse(subject);
    }

    private SubjectResponse toSubjectResponse(Subject subject) {
        return SubjectResponse.builder()
                .id(subject.getId())
                .code(subject.getCode())
                .name(subject.getName())
                .description(subject.getDescription())
                .build();
    }
}
