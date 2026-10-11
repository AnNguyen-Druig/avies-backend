package com.avies.backend.question;

import com.avies.backend.dto.ai.GeneratedQuestionDto;
import com.avies.backend.dto.request.QuestionGenerateRequest;
import com.avies.backend.dto.response.QuestionResponse;
import com.avies.backend.entity.*;
import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.entity.enums.QuestionSourceType;
import com.avies.backend.entity.enums.QuestionStatus;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.repository.*;
import com.avies.backend.service.AiQuestionGenerationService;
import com.avies.backend.service.OpenAiEmbeddingService;
import com.avies.backend.service.QuestionService;
import com.avies.backend.service.impl.QuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Kiểm thử logic nghiệp vụ sinh câu hỏi tự động (QuestionServiceImpl.generateQuestions)")
class QuestionGenerateServiceTest {

    @Mock private QuestionRepository questionRepository;
    @Mock private SubjectRepository subjectRepository;
    @Mock private UserRepository userRepository;
    @Mock private LecturerSubjectAssignmentRepository assignmentRepository;
    @Mock private QuestionAuditLogRepository auditRepository;
    @Mock private OpenAiEmbeddingService openAiEmbeddingService;
    @Mock private LearningMaterialChunkRepository chunkRepository;
    @Mock private AiQuestionGenerationService aiQuestionGenerationService;

    private QuestionService questionService;

    private User lecturer;
    private Subject subject;

    @BeforeEach
    void setUp() {
        questionService = new QuestionServiceImpl(
                questionRepository,
                subjectRepository,
                userRepository,
                assignmentRepository,
                auditRepository,
                openAiEmbeddingService,
                chunkRepository,
                aiQuestionGenerationService
        );

        Role lecturerRole = Role.builder().id(2L).code("LECTURER").name("Giảng viên").build();
        lecturer = User.builder()
                .id(10L)
                .username("gv_nguyen")
                .fullName("Nguyễn Văn A")
                .role(lecturerRole)
                .build();

        subject = Subject.builder()
                .id(1L)
                .code("DSA101")
                .name("Cấu trúc dữ liệu và giải thuật")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        lecturer.getUsername(),
                        "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_LECTURER"))
                )
        );
        lenient().when(userRepository.findByUsername("gv_nguyen")).thenReturn(Optional.of(lecturer));
    }

    @Test
    @DisplayName("Sinh câu hỏi thành công: lưu Question & Rubric ở trạng thái DRAFT, sourceType = AI, isAiGenerated = true")
    void generateQuestionsSuccessFlow() {
        // Given
        when(assignmentRepository.existsByLecturerIdAndSubjectId(lecturer.getId(), subject.getId())).thenReturn(true);
        when(subjectRepository.findById(subject.getId())).thenReturn(Optional.of(subject));

        // Mock OpenAI embedding: trả về vector 1536 dims
        float[] vector = new float[1536];
        Arrays.fill(vector, 0.05f);
        when(openAiEmbeddingService.generateEmbeddings(List.of("Cây nhị phân"))).thenReturn(List.of(vector));

        // Mock chunks từ DB
        LearningMaterialChunkRepository.ChunkView chunk1 = mock(LearningMaterialChunkRepository.ChunkView.class);
        when(chunk1.getMaterialTitle()).thenReturn("Giáo trình Cấu trúc dữ liệu");
        when(chunk1.getContent()).thenReturn("Cây nhị phân là cây trong đó mỗi nút có tối đa hai nút con.");
        when(chunkRepository.searchSimilarChunks(eq(subject.getId()), anyString(), eq(5)))
                .thenReturn(List.of(chunk1));

        // Mock AI LLM response
        GeneratedQuestionDto.GeneratedCriterionDto c1 = GeneratedQuestionDto.GeneratedCriterionDto.builder()
                .criterionText("Nêu đúng định nghĩa cây nhị phân")
                .pointWeight(new BigDecimal("5.00"))
                .isMandatory(false)
                .displayOrder(1)
                .build();
        GeneratedQuestionDto.GeneratedCriterionDto c2 = GeneratedQuestionDto.GeneratedCriterionDto.builder()
                .criterionText("Giải thích các phép duyệt cây")
                .pointWeight(new BigDecimal("5.00"))
                .isMandatory(false)
                .displayOrder(2)
                .build();

        GeneratedQuestionDto.GeneratedRubricDto rubricDto = GeneratedQuestionDto.GeneratedRubricDto.builder()
                .title("Rubric chấm câu hỏi Cây nhị phân")
                .description("Đánh giá sinh viên theo thang điểm 10.")
                .criteria(List.of(c1, c2))
                .build();

        GeneratedQuestionDto aiGenerated = GeneratedQuestionDto.builder()
                .content("Anh/chị hãy định nghĩa cây nhị phân và trình bày các cách duyệt cây?")
                .referenceAnswer("Cây nhị phân có tối đa 2 con (trái, phải). Các cách duyệt: Tiền thứ tự, Trung thứ tự, Hậu thứ tự.")
                .bloomLevel(BloomLevel.UNDERSTAND)
                .topics(Set.of("Cây nhị phân", "Cấu trúc dữ liệu"))
                .answerTimeLimitSeconds(120)
                .rubric(rubricDto)
                .build();

        when(aiQuestionGenerationService.generateQuestions(eq(subject.getName()), eq("Cây nhị phân"), anyString(), eq(BloomLevel.UNDERSTAND), eq(1)))
                .thenReturn(List.of(aiGenerated));

        when(questionRepository.saveAndFlush(any(Question.class))).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            q.setId(50L);
            if (q.getRubric() != null) {
                q.getRubric().setId(60L);
            }
            return q;
        });

        QuestionGenerateRequest request = QuestionGenerateRequest.builder()
                .subjectId(subject.getId())
                .keyword("Cây nhị phân")
                .bloomLevel(BloomLevel.UNDERSTAND)
                .count(1)
                .build();

        // When
        List<QuestionResponse> responses = questionService.generateQuestions(request);

        // Then
        assertThat(responses).hasSize(1);
        QuestionResponse res = responses.get(0);
        assertThat(res.getId()).isEqualTo(50L);
        assertThat(res.getContent()).contains("Anh/chị hãy định nghĩa cây nhị phân");
        assertThat(res.getSourceType()).isEqualTo(QuestionSourceType.AI);
        assertThat(res.getStatus()).isEqualTo(QuestionStatus.DRAFT);
        assertThat(res.getBloomLevel()).isEqualTo(BloomLevel.UNDERSTAND);

        // Verify rubric details
        assertThat(res.getRubricId()).isEqualTo(60L);
        assertThat(res.getRubric()).isNotNull();
        assertThat(res.getRubric().getTitle()).isEqualTo("Rubric chấm câu hỏi Cây nhị phân");
        assertThat(res.getRubric().getCriteria()).hasSize(2);

        // Verify repository interactions
        verify(openAiEmbeddingService).generateEmbeddings(List.of("Cây nhị phân"));
        verify(chunkRepository).searchSimilarChunks(eq(subject.getId()), anyString(), eq(5));
        verify(aiQuestionGenerationService).generateQuestions(eq(subject.getName()), eq("Cây nhị phân"), anyString(), eq(BloomLevel.UNDERSTAND), eq(1));
        verify(questionRepository).saveAndFlush(any(Question.class));
        verify(auditRepository).save(any(QuestionAuditLog.class));
    }

    @Test
    @DisplayName("Ném lỗi NO_RELEVANT_MATERIALS khi môn học không có đoạn tài liệu nào")
    void generateQuestionsThrowsWhenNoChunksFound() {
        when(assignmentRepository.existsByLecturerIdAndSubjectId(lecturer.getId(), subject.getId())).thenReturn(true);
        when(subjectRepository.findById(subject.getId())).thenReturn(Optional.of(subject));
        when(openAiEmbeddingService.generateEmbeddings(anyList())).thenReturn(List.of(new float[1536]));
        when(chunkRepository.searchSimilarChunks(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());

        QuestionGenerateRequest request = QuestionGenerateRequest.builder()
                .subjectId(subject.getId())
                .keyword("Kiến thức chưa có tài liệu")
                .build();

        assertThatThrownBy(() -> questionService.generateQuestions(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NO_RELEVANT_MATERIALS);

        verifyNoInteractions(aiQuestionGenerationService);
        verify(questionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Ném lỗi SUBJECT_NOT_ASSIGNED khi giảng viên chưa được phân công môn học")
    void generateQuestionsThrowsWhenNotAssigned() {
        when(assignmentRepository.existsByLecturerIdAndSubjectId(lecturer.getId(), subject.getId())).thenReturn(false);

        QuestionGenerateRequest request = QuestionGenerateRequest.builder()
                .subjectId(subject.getId())
                .keyword("Từ khóa bất kỳ")
                .build();

        assertThatThrownBy(() -> questionService.generateQuestions(request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBJECT_NOT_ASSIGNED);

        verifyNoInteractions(openAiEmbeddingService);
        verifyNoInteractions(chunkRepository);
        verifyNoInteractions(aiQuestionGenerationService);
    }
}
