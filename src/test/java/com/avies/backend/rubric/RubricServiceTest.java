package com.avies.backend.rubric;

import com.avies.backend.dto.request.RubricCreationRequest;
import com.avies.backend.dto.request.RubricCriterionRequest;
import com.avies.backend.dto.request.RubricUpdateRequest;
import com.avies.backend.dto.response.RubricResponse;
import com.avies.backend.entity.Rubric;
import com.avies.backend.entity.RubricCriterion;
import com.avies.backend.entity.Subject;
import com.avies.backend.entity.User;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.RubricRepository;
import com.avies.backend.service.impl.RubricServiceImpl;
import com.avies.backend.service.support.SubjectAccessHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RubricServiceTest {

    @Mock
    RubricRepository rubricRepository;

    @Mock
    SubjectAccessHelper subjectAccessHelper;

    @InjectMocks
    RubricServiceImpl rubricService;

    User lecturer;
    Subject subject;

    @BeforeEach
    void setUp() {
        lecturer = User.builder().id(1L).username("lecturer1").build();
        subject = Subject.builder().id(10L).code("CS101").name("Lap trinh Java").build();
    }

    @Test
    void createRubric_success_whenTotalWeightIs10() {
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(subjectAccessHelper.requireAssignmentAndGetSubject(lecturer, 10L)).thenReturn(subject);
        when(rubricRepository.existsBySubjectId(10L)).thenReturn(false);
        when(rubricRepository.save(any(Rubric.class))).thenAnswer(invocation -> {
            Rubric r = invocation.getArgument(0);
            r.setId(100L);
            return r;
        });

        RubricCreationRequest request = RubricCreationRequest.builder()
                .subjectId(10L)
                .title("Rubric chuẩn")
                .description("Mô tả rubric")
                .criteria(List.of(
                        RubricCriterionRequest.builder().criterionText("Tiêu chí 1").pointWeight(new BigDecimal("6.00")).isMandatory(true).displayOrder(1).build(),
                        RubricCriterionRequest.builder().criterionText("Tiêu chí 2").pointWeight(new BigDecimal("4.00")).isMandatory(false).displayOrder(2).build()
                ))
                .build();

        RubricResponse response = rubricService.createRubric(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getSubjectId()).isEqualTo(10L);
        assertThat(response.getCriteria()).hasSize(2);
        verify(rubricRepository).save(any(Rubric.class));
    }

    @Test
    void createRubric_throwsWhenWeightNot10() {
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(subjectAccessHelper.requireAssignmentAndGetSubject(lecturer, 10L)).thenReturn(subject);
        when(rubricRepository.existsBySubjectId(10L)).thenReturn(false);

        RubricCreationRequest request = RubricCreationRequest.builder()
                .subjectId(10L)
                .criteria(List.of(
                        RubricCriterionRequest.builder().criterionText("Tiêu chí 1").pointWeight(new BigDecimal("5.00")).build()
                ))
                .build();

        assertThatThrownBy(() -> rubricService.createRubric(request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RUBRIC_CRITERIA_INVALID));
    }

    @Test
    void createRubric_throwsWhenCriteriaEmpty() {
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(subjectAccessHelper.requireAssignmentAndGetSubject(lecturer, 10L)).thenReturn(subject);
        when(rubricRepository.existsBySubjectId(10L)).thenReturn(false);

        RubricCreationRequest request = RubricCreationRequest.builder()
                .subjectId(10L)
                .criteria(List.of())
                .build();

        assertThatThrownBy(() -> rubricService.createRubric(request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RUBRIC_CRITERIA_EMPTY));
    }

    @Test
    void createRubric_throwsWhenAlreadyExists() {
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(subjectAccessHelper.requireAssignmentAndGetSubject(lecturer, 10L)).thenReturn(subject);
        when(rubricRepository.existsBySubjectId(10L)).thenReturn(true);

        RubricCreationRequest request = RubricCreationRequest.builder()
                .subjectId(10L)
                .criteria(List.of(
                        RubricCriterionRequest.builder().criterionText("Tiêu chí 1").pointWeight(new BigDecimal("10.00")).build()
                ))
                .build();

        assertThatThrownBy(() -> rubricService.createRubric(request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RUBRIC_EXISTED));
    }

    @Test
    void getRubricById_success() {
        Rubric rubric = Rubric.builder()
                .id(100L)
                .subject(subject)
                .title("Rubric Java")
                .criteria(new ArrayList<>())
                .build();

        when(rubricRepository.findById(100L)).thenReturn(Optional.of(rubric));
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);

        RubricResponse response = rubricService.getRubricById(100L);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getSubjectId()).isEqualTo(10L);
        verify(subjectAccessHelper).requireAssignment(lecturer, 10L);
    }

    @Test
    void getRubricBySubjectId_notFound() {
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(subjectAccessHelper.requireAssignmentAndGetSubject(lecturer, 10L)).thenReturn(subject);
        when(rubricRepository.findBySubjectId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rubricService.getRubricBySubjectId(10L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.RUBRIC_NOT_FOUND));
    }

    @Test
    void updateRubric_success() {
        Rubric rubric = Rubric.builder()
                .id(100L)
                .subject(subject)
                .title("Rubric Cũ")
                .criteria(new ArrayList<>())
                .build();

        when(rubricRepository.findById(100L)).thenReturn(Optional.of(rubric));
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(rubricRepository.save(any(Rubric.class))).thenReturn(rubric);

        RubricUpdateRequest request = RubricUpdateRequest.builder()
                .title("Rubric Mới")
                .description("Mô tả mới")
                .criteria(List.of(
                        RubricCriterionRequest.builder().criterionText("Tiêu chí 1").pointWeight(new BigDecimal("10.00")).build()
                ))
                .build();

        RubricResponse response = rubricService.updateRubric(100L, request);

        assertThat(response).isNotNull();
        assertThat(rubric.getTitle()).isEqualTo("Rubric Mới");
        assertThat(rubric.getCriteria()).hasSize(1);
    }

    @Test
    void updateRubric_onlyTitleAndDescription_keepsExistingCriteria() {
        RubricCriterion existingCriterion = RubricCriterion.builder()
                .id(1L)
                .criterionText("Tiêu chí cũ")
                .pointWeight(new BigDecimal("10.00"))
                .build();
        List<RubricCriterion> criteriaList = new ArrayList<>();
        criteriaList.add(existingCriterion);

        Rubric rubric = Rubric.builder()
                .id(100L)
                .subject(subject)
                .title("Rubric Cũ")
                .description("Mô tả cũ")
                .criteria(criteriaList)
                .build();

        when(rubricRepository.findById(100L)).thenReturn(Optional.of(rubric));
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);
        when(rubricRepository.save(any(Rubric.class))).thenReturn(rubric);

        RubricUpdateRequest request = RubricUpdateRequest.builder()
                .title("Tiêu đề được đổi")
                .description("Mô tả được đổi")
                .criteria(null) // Không truyền criteria
                .build();

        RubricResponse response = rubricService.updateRubric(100L, request);

        assertThat(response).isNotNull();
        assertThat(rubric.getTitle()).isEqualTo("Tiêu đề được đổi");
        assertThat(rubric.getDescription()).isEqualTo("Mô tả được đổi");
        assertThat(rubric.getCriteria()).hasSize(1);
        assertThat(rubric.getCriteria().get(0).getCriterionText()).isEqualTo("Tiêu chí cũ");
    }

    @Test
    void deleteRubric_success() {
        Rubric rubric = Rubric.builder()
                .id(100L)
                .subject(subject)
                .build();

        when(rubricRepository.findById(100L)).thenReturn(Optional.of(rubric));
        when(subjectAccessHelper.getCurrentActor()).thenReturn(lecturer);

        rubricService.deleteRubric(100L);

        verify(subjectAccessHelper).requireAssignment(lecturer, 10L);
        verify(rubricRepository).delete(rubric);
    }
}
