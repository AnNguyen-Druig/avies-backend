package com.avies.backend.service;

import com.avies.backend.dto.request.RubricCreationRequest;
import com.avies.backend.dto.request.RubricUpdateRequest;
import com.avies.backend.dto.response.RubricResponse;

public interface RubricService {

    /**
     * Tạo mới Rubric cho một môn học (yêu cầu phân công giảng viên hoặc quyền admin).
     */
    RubricResponse createRubric(RubricCreationRequest request);

    /**
     * Lấy thông tin chi tiết Rubric theo ID.
     */
    RubricResponse getRubricById(Long id);

    /**
     * Lấy thông tin Rubric theo môn học (subjectId).
     */
    RubricResponse getRubricBySubjectId(Long subjectId);

    /**
     * Cập nhật thông tin Rubric và các tiêu chí đánh giá.
     */
    RubricResponse updateRubric(Long id, RubricUpdateRequest request);

    /**
     * Xóa Rubric của môn học.
     */
    void deleteRubric(Long id);
}
