package com.avies.backend.exam;

import com.avies.backend.entity.enums.BloomLevel;
import com.avies.backend.service.support.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.IntStream;
import static com.avies.backend.exam.ExamQuestionTestData.candidate;
import static org.assertj.core.api.Assertions.*;

/** Các ca random dùng seed cố định để kết quả test không phụ thuộc timing hoặc SecureRandom production. */
class ExamQuestionSelectorTest {
    /** Mốc chỉ dùng so thứ tự lịch sử, không cần clock hiện tại. */
    private final OffsetDateTime time = OffsetDateTime.parse("2026-10-01T08:00:00+07:00");

    /** Đúng N distinct và thứ tự lặp lại khi cùng seed/input. */
    @Test
    void exactlyNAndInjectableSeedAreDeterministic() {
        var pool = pool(8);
        var first = new ExamQuestionSelector(new Random(19)).choose(pool, List.of(), 4);
        var second = new ExamQuestionSelector(new Random(19)).choose(pool, List.of(), 4);
        assertThat(first).isEqualTo(second).hasSize(4);
        assertThat(first.stream().map(ExamQuestionEligibility.Candidate::id).distinct()).hasSize(4);
    }

    /** Khi đủ pool, không dùng lại bất kỳ câu nào của sinh viên thi ngay trước. */
    @Test
    void avoidsImmediatelyPreviousExamWhenPoolPermits() {
        var history = List.of(use(1, 1, time, 1), use(2, 1, time, 2));
        assertThat(selector().choose(pool(4), history, 2)).extracting(ExamQuestionEligibility.Candidate::id)
                .containsExactlyInAnyOrder(3L, 4L);
    }

    /** Câu chưa dùng được ưu tiên hơn câu từng dùng nhưng không thuộc sinh viên trước. */
    @Test
    void unusedQuestionsBeatPreviouslyUsedOnes() {
        var history = List.of(use(1, 1, time, 1), use(2, 2, time.plusMinutes(20), 2));
        assertThat(selector().choose(pool(3), history, 1).getFirst().id()).isEqualTo(3);
    }

    /** Khi tất cả từng dùng, chọn câu xuất hiện xa nhất trước thay vì giảm số câu. */
    @Test
    void olderUseWinsWhenRepetitionIsUnavoidable() {
        var history = List.of(use(1, 1, time, 1), use(2, 2, time.plusMinutes(20), 2), use(3, 3, time.plusMinutes(40), 3));
        assertThat(selector().choose(pool(3), history, 1).getFirst().id()).isEqualTo(1);
    }

    /** Không đủ câu tránh hoàn toàn overlap thì vẫn chọn đủ distinct trong một bài. */
    @Test
    void fallbackMinimizesOverlapWithoutDuplicatingWithinExam() {
        var history = List.of(use(1, 1, time, 1), use(2, 1, time, 2));
        var result = selector().choose(pool(3), history, 3);
        assertThat(result).hasSize(3).extracting(ExamQuestionEligibility.Candidate::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(result.getFirst().id()).isEqualTo(3);
    }

    /** Sinh viên đã bắt đầu thực tế được xem là previous trước các bộ chỉ mới chuẩn bị. */
    @Test
    void actualExamTimeTakesPrecedenceOverUnstartedSelectionIds() {
        var history = List.of(use(1, 1, time, 1), use(2, 2, null, 999));
        assertThat(selector().choose(pool(2), history, 1).getFirst().id()).isEqualTo(2);
    }

    /** Chưa có thời gian thi thì ID liên kết gần nhất là fallback được mô tả rõ. */
    @Test
    void fallsBackToMostRecentlyPersistedExamWhenNoActualTimeExists() {
        var history = List.of(use(1, 1, null, 1), use(2, 2, null, 2));
        assertThat(selector().choose(pool(2), history, 1).getFirst().id()).isEqualTo(1);
    }

    /** Tần suất ít hơn phá hòa khi hai câu có cùng lần dùng gần nhất. */
    @Test
    void usesFrequencyOnlyAfterRecency() {
        var history = List.of(use(1, 1, time, 1), use(1, 2, time.plusMinutes(20), 2), use(2, 2, time.plusMinutes(20), 3));
        assertThat(selector().choose(pool(2), history, 1).getFirst().id()).isEqualTo(2);
    }

    /** Cân Bloom khi mọi ưu tiên chống lặp bằng nhau, không cam kết phân phối cưỡng bức. */
    @Test
    void balancesBloomAmongEquallyRankedCandidates() {
        var pool = List.of(candidate(1, BloomLevel.REMEMBER), candidate(2, BloomLevel.REMEMBER),
                candidate(3, BloomLevel.APPLY), candidate(4, BloomLevel.APPLY), candidate(5, BloomLevel.ANALYZE));
        assertThat(selector().choose(pool, List.of(), 3)).extracting(ExamQuestionEligibility.Candidate::bloom)
                .containsExactlyInAnyOrder(BloomLevel.REMEMBER, BloomLevel.APPLY, BloomLevel.ANALYZE);
    }

    /** Ưu tiên không lặp cao hơn việc đủ Bloom, nên không quảng cáo exact balance. */
    @Test
    void bloomDoesNotOverrideRepetitionPolicy() {
        var pool = List.of(candidate(1, BloomLevel.REMEMBER), candidate(2, BloomLevel.APPLY), candidate(3, BloomLevel.REMEMBER));
        assertThat(selector().choose(pool, List.of(use(2, 1, time, 1)), 2))
                .extracting(ExamQuestionEligibility.Candidate::bloom).containsOnly(BloomLevel.REMEMBER);
    }

    /** Nhiều seed cho kết quả khác nhau, không luôn trả ID thấp nhất theo query order. */
    @Test
    void randomizesEqualPriorityCandidates() {
        Set<List<Long>> outputs = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) {
            outputs.add(new ExamQuestionSelector(new Random(seed)).choose(pool(8), List.of(), 3).stream()
                    .map(ExamQuestionEligibility.Candidate::id).toList());
        }
        assertThat(outputs.size()).isGreaterThan(1);
    }

    /** Không có đường trả bộ nhỏ hơn N hoặc cùng câu hai lần. */
    @Test
    void rejectsInvalidPoolInsteadOfSilentlyReturningFewerQuestions() {
        assertThatThrownBy(() -> selector().choose(pool(2), List.of(), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector().choose(List.of(candidate(1, BloomLevel.APPLY), candidate(1, BloomLevel.APPLY)), List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Tạo pool đồng Bloom để tách kiểm tra chống lặp khỏi Bloom. */
    private List<ExamQuestionEligibility.Candidate> pool(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> candidate(i, BloomLevel.UNDERSTAND)).toList();
    }

    /** Mỗi test có nguồn random riêng. */
    private ExamQuestionSelector selector() { return new ExamQuestionSelector(new Random(17)); }

    /** Lịch sử giả đúng cấu trúc unique câu trong mỗi bài. */
    private ExamQuestionSelector.Use use(long question, long exam, OffsetDateTime started, long id) {
        return new ExamQuestionSelector.Use(exam, question, started, null, id);
    }
}
