package com.avies.backend.exam;

import com.avies.backend.entity.*;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import com.avies.backend.service.support.ExamSlotPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Kiểm thử thuật toán thuần, không dùng clock địa phương, database hoặc dịch vụ bên ngoài. */
class ExamSlotPlannerTest {
    /** Bộ xếp lịch không có trạng thái chia sẻ giữa các request. */
    private final ExamSlotPlanner planner = new ExamSlotPlanner();
    /** Mốc tương lai có giây và nano để phát hiện làm tròn mất chính xác. */
    private final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(7);

    /** Cấp tuần tự, không chồng lấn, slot cuối khớp chính xác cửa sổ. */
    @Test
    void sequentialSlotsFitExactlyAndDoNotOverlap() {
        var result = planner.plan(session(60, 20), List.of(), 3);
        assertThat(result).extracting(ExamSlotPlanner.Slot::order).containsExactly(1, 2, 3);
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i).start()).isEqualTo(start.plusMinutes(i * 20L));
            assertThat(result.get(i).end()).isEqualTo(start.plusMinutes((i + 1L) * 20));
        }
    }

    /** Phần dư nhỏ hơn một slot không được tính là sức chứa bổ sung. */
    @Test
    void capacityUsesWholeSlotsNotRoundedUp() {
        assertCode(() -> planner.plan(session(59, 20), List.of(), 3), ErrorCode.EXAM_SLOT_CAPACITY_EXCEEDED);
        assertThat(planner.plan(session(59, 20), List.of(), 2)).hasSize(2);
    }

    /** Tái dùng lỗ nhỏ nhất; dữ liệu lịch cũ không được thay đổi hoặc xếp lại. */
    @Test
    void reusesHolesWithoutMovingExistingSchedules() {
        var third = exam(3, 20);
        var first = exam(1, 20);
        var result = planner.plan(session(120, 20), List.of(third, first), 2);
        assertThat(result).extracting(ExamSlotPlanner.Slot::order).containsExactly(2, 4);
        assertThat(third.getSlotOrder()).isEqualTo(3);
        assertThat(third.getScheduledStartAt()).isEqualTo(start.plusMinutes(40));
        assertThat(first.getScheduledStartAt()).isEqualTo(start);
    }

    /** Timestamp khác offset nhưng cùng instant vẫn biểu diễn đúng một slot. */
    @Test
    void comparesInstantsRatherThanOffsetText() {
        var old = exam(1, 20);
        old.setScheduledStartAt(start.withOffsetSameInstant(ZoneOffset.UTC));
        old.setScheduledEndAt(start.plusMinutes(20).withOffsetSameInstant(ZoneOffset.ofHours(-4)));
        var session = session(40, 20);
        session.setScheduledEndAt(session.getScheduledEndAt().withOffsetSameInstant(ZoneOffset.UTC));
        assertThat(planner.plan(session, List.of(old), 1).getFirst().order()).isEqualTo(2);
    }

    /** Không tự điền lịch legacy, kể cả request lặp không cần slot mới. */
    @ParameterizedTest
    @ValueSource(strings = {"slot", "start", "end", "mismatch", "outside", "duplicate"})
    void refusesAmbiguousOrMisalignedExistingSchedules(String issue) {
        var old = exam(1, 20);
        switch (issue) {
            case "slot" -> old.setSlotOrder(null);
            case "start" -> old.setScheduledStartAt(null);
            case "end" -> old.setScheduledEndAt(null);
            case "mismatch" -> old.setScheduledStartAt(start.plusMinutes(1));
            case "outside" -> old.setSlotOrder(100);
            default -> { }
        }
        List<StudentExam> rows = issue.equals("duplicate") ? List.of(old, exam(1, 20)) : List.of(old);
        assertCode(() -> planner.plan(session(120, 20), rows, 0), ErrorCode.EXAM_SCHEDULE_UNAVAILABLE);
    }

    /** Thiếu hoặc hết hiệu lực lịch phiên phải chặn assignment thay vì tạo lịch từ createdAt. */
    @ParameterizedTest
    @ValueSource(strings = {"start", "end", "duration", "zero", "past", "equal"})
    void rejectsInvalidParentSchedules(String issue) {
        var session = session(120, 20);
        switch (issue) {
            case "start" -> session.setScheduledStartAt(null);
            case "end" -> session.setScheduledEndAt(null);
            case "duration" -> session.setSlotDurationMinutes(null);
            case "zero" -> session.setSlotDurationMinutes(0);
            case "past" -> session.setScheduledStartAt(start.minusYears(10));
            case "equal" -> session.setScheduledEndAt(start);
        }
        assertCode(() -> planner.plan(session, List.of(), 1), ErrorCode.EXAM_SCHEDULE_UNAVAILABLE);
    }

    /** Cửa sổ hợp lệ nhưng ngắn hơn một slot có sức chứa bằng không. */
    @Test
    void validWindowCanHaveZeroCapacity() {
        assertCode(() -> planner.plan(session(10, 20), List.of(), 1), ErrorCode.EXAM_SLOT_CAPACITY_EXCEEDED);
    }

    /** Gửi lại batch đã đủ lịch không cần slot mới, kể cả phiên đã đầy. */
    @Test
    void repeatWithoutNewStudentsDoesNotNeedFreeCapacity() {
        assertThat(planner.plan(session(20, 20), List.of(exam(1, 20)), 0)).isEmpty();
    }

    /** Miền slot_order INTEGER được giữ ngay cả cửa sổ dài hơn miền biểu diễn. */
    @Test
    void largeWindowsDoNotOverflowIntegerOrMinuteArithmetic() {
        var session = session(20, 1);
        session.setScheduledEndAt(start.plusYears(10000));
        assertThat(planner.plan(session, List.of(), 1).getFirst().order()).isEqualTo(1);
    }

    /** Tạo parent thuần với cửa sổ xác định, không sinh ID giả cho repository. */
    private ExamSession session(long windowMinutes, int duration) {
        return ExamSession.builder().scheduledStartAt(start).scheduledEndAt(start.plusMinutes(windowMinutes))
                .slotDurationMinutes(duration).build();
    }

    /** Lịch cũ bám lưới slot của phiên theo instant. */
    private StudentExam exam(int order, int duration) {
        return StudentExam.builder().slotOrder(order).scheduledStartAt(start.plusMinutes((order - 1L) * duration))
                .scheduledEndAt(start.plusMinutes(order * (long) duration)).build();
    }

    /** Đối chiếu ErrorCode mà không phụ thuộc ngôn ngữ message. */
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
