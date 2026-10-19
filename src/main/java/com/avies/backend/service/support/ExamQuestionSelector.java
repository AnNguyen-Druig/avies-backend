package com.avies.backend.service.support;

import com.avies.backend.entity.enums.BloomLevel;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Thuật toán thuần: tránh sinh viên trước, ưu tiên câu chưa dùng/cũ hơn, cân Bloom khi ngang ưu tiên.
 */
@Component
public class ExamQuestionSelector {
    /**
     * Nguồn random inject bằng constructor trong test, không dùng random toàn cục có seed cố định.
     */
    private final RandomGenerator random;

    /**
     * Spring dùng nguồn random khó đoán, không có trạng thái nghiệp vụ lưu giữa các lần chọn.
     */
    public ExamQuestionSelector() {
        this(new SecureRandom());
    }

    /**
     * Cho phép fixture seed cố định hoặc nguồn random điều khiển được, không thay config production.
     */
    public ExamQuestionSelector(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Lần dùng của một câu; selectionId là fallback thứ tự nếu chưa có bài nào bắt đầu thực tế.
     */
    public record Use(
            /** Bài thi khác trong cùng phiên. */Long studentExamId,
            /** Câu đã dùng. */Long questionId,
            /** Giờ bắt đầu thực tế có thể chưa có. */OffsetDateTime startedAt,
            /** Giờ hoàn tất dùng khi thiếu startedAt legacy. */OffsetDateTime completedAt,
            /** ID liên kết tăng dần làm thứ tự chọn fallback. */Long selectionId) {
    }

    /**
     * Thứ tự ưu tiên lexicographic; Bloom chỉ phá hòa sau các tiêu chí chống lặp.
     */
    private record Priority(
            /** Tránh bài gần nhất. */int previous,
            /** Chưa từng dùng được ưu tiên. */int used,
            /** Câu dùng xa hơn được ưu tiên. */int recency,
            /** Ít lần xuất hiện hơn được ưu tiên khi cùng độ gần. */int count,
            /** Bloom ít có trong bộ đang xây được ưu tiên cuối. */int bloomCount) implements Comparable<Priority> {
        /**
         * So từ ưu tiên chống lặp tới Bloom, không cộng trọng số làm mất thứ tự chính sách.
         */
        @Override
        public int compareTo(Priority other) {
            int[] a = {previous, used, recency, count, bloomCount};
            int[] b = {other.previous, other.used, other.recency, other.count, other.bloomCount};
            for (int i = 0; i < a.length; i++) {
                int comparison = Integer.compare(a[i], b[i]);
                if (comparison != 0) return comparison;
            }
            return 0;
        }
    }

    /**
     * Chọn đúng N distinct khi caller đã xác nhận đủ pool; không tạo câu AI hoặc lặp trong một bài.
     */
    public List<ExamQuestionEligibility.Candidate> choose(List<ExamQuestionEligibility.Candidate> pool, List<Use> history, int count) {
        if (count < 1 || pool.size() < count || pool.stream().map(ExamQuestionEligibility.Candidate::id).distinct().count() != pool.size())
            throw new IllegalArgumentException("Pool hoặc số câu không hợp lệ.");
        Map<Long, List<Use>> exams = new HashMap<>();
        history.forEach(use -> exams.computeIfAbsent(use.studentExamId(), ignored -> new ArrayList<>()).add(use));
        List<Long> ordered = new ArrayList<>(exams.keySet());
        ordered.sort(Comparator.<Long, OffsetDateTime>comparing(id -> actualTime(exams.get(id)),
                        Comparator.nullsFirst(Comparator.comparing(OffsetDateTime::toInstant)))
                .thenComparingLong(id -> maxSelection(exams.get(id))));
        Long previous = ordered.isEmpty() ? null : ordered.getLast();
        Map<Long, Integer> lastRank = new HashMap<>();
        Map<Long, Integer> uses = new HashMap<>();
        Set<Long> previousQuestions = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            for (Use use : exams.get(ordered.get(i))) {
                lastRank.put(use.questionId(), i + 1);
                uses.merge(use.questionId(), 1, Integer::sum);
                if (Objects.equals(previous, use.studentExamId())) previousQuestions.add(use.questionId());
            }
        }
        List<ExamQuestionEligibility.Candidate> remaining = new ArrayList<>(pool);
        remaining.sort(Comparator.comparing(ExamQuestionEligibility.Candidate::id));
        Map<BloomLevel, Integer> blooms = new EnumMap<>(BloomLevel.class);
        List<ExamQuestionEligibility.Candidate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Priority best = null;
            List<ExamQuestionEligibility.Candidate> ties = new ArrayList<>();
            for (var candidate : remaining) {
                var priority = new Priority(previousQuestions.contains(candidate.id()) ? 1 : 0,
                        uses.containsKey(candidate.id()) ? 1 : 0, lastRank.getOrDefault(candidate.id(), 0),
                        uses.getOrDefault(candidate.id(), 0), blooms.getOrDefault(candidate.bloom(), 0));
                int comparison = best == null ? -1 : priority.compareTo(best);
                if (comparison < 0) {
                    best = priority;
                    ties.clear();
                }
                if (comparison <= 0) ties.add(candidate);
            }
            var selected = ties.get(random.nextInt(ties.size()));
            remaining.remove(selected);
            blooms.merge(selected.bloom(), 1, Integer::sum);
            result.add(selected);
        }
        return List.copyOf(result);
    }

    /**
     * Ưu tiên thứ tự thi thực tế; completedAt chỉ bù khi legacy không có startedAt.
     */
    private OffsetDateTime actualTime(List<Use> uses) {
        return uses.stream().map(use -> use.startedAt() != null ? use.startedAt() : use.completedAt())
                .filter(Objects::nonNull).max(Comparator.comparing(OffsetDateTime::toInstant)).orElse(null);
    }

    /**
     * Mọi câu của một bài dùng cùng recency; không xem questionOrder là thứ tự sinh viên thi.
     */
    private long maxSelection(List<Use> uses) {
        return uses.stream().mapToLong(Use::selectionId).max().orElse(0);
    }
}
