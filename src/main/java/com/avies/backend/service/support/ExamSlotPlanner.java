package com.avies.backend.service.support;

import com.avies.backend.entity.ExamSession;
import com.avies.backend.entity.StudentExam;
import com.avies.backend.exception.AppException;
import com.avies.backend.exception.ErrorCode;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bộ xếp slot thuần: kiểm tra lịch hiện có, tái dùng lỗ nhỏ nhất, không thay đổi entity cũ. */
@Component
public class ExamSlotPlanner {
    /** Slot bất biến; thứ tự bắt đầu từ 1, timestamp giữ offset của mốc phiên khi tính toán. */
    public record Slot(
            /** Thứ tự duy nhất trong phiên. */ int order,
            /** Bắt đầu dự kiến. */ OffsetDateTime start,
            /** Kết thúc dự kiến. */ OffsetDateTime end) { }

    /** Kiểm tra mọi lịch cũ rồi cấp slot trống nhỏ nhất; caller phải giữ khóa parent đến commit. */
    public List<Slot> plan(ExamSession session, List<StudentExam> existing, int count) {
        OffsetDateTime start = session.getScheduledStartAt();
        OffsetDateTime end = session.getScheduledEndAt();
        Integer minutes = session.getSlotDurationMinutes();
        if (start == null || end == null || minutes == null || minutes <= 0
                || !end.isAfter(start) || !start.isAfter(OffsetDateTime.now()))
            throw new AppException(ErrorCode.EXAM_SCHEDULE_UNAVAILABLE);
        long capacity = Math.min(Integer.MAX_VALUE, Duration.between(start, end).getSeconds() / (minutes * 60L));
        var occupied = new HashSet<Integer>();
        for (StudentExam exam : existing) {
            Integer slot = exam.getSlotOrder();
            if (slot == null || slot < 1 || slot > capacity || exam.getScheduledStartAt() == null
                    || exam.getScheduledEndAt() == null || !occupied.add(slot)
                    || !exam.getScheduledStartAt().isEqual(start.plusMinutes((slot - 1L) * minutes))
                    || !exam.getScheduledEndAt().isEqual(start.plusMinutes(slot.longValue() * minutes)))
                throw new AppException(ErrorCode.EXAM_SCHEDULE_UNAVAILABLE);
        }
        if (count > capacity - occupied.size()) throw new AppException(ErrorCode.EXAM_SLOT_CAPACITY_EXCEEDED);
        List<Slot> result = new ArrayList<>();
        long cursor = 1;
        for (int i = 0; i < count; i++) {
            while (occupied.contains(Math.toIntExact(cursor))) cursor++;
            int order = Math.toIntExact(cursor);
            result.add(new Slot(order, start.plusMinutes((cursor - 1) * minutes), start.plusMinutes(cursor * minutes)));
            cursor++;
        }
        return result;
    }
}
