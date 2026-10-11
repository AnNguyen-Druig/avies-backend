package com.avies.backend.service;

import com.avies.backend.dto.request.SubjectCreationRequest;
import com.avies.backend.dto.request.SubjectUpdateRequest;
import com.avies.backend.dto.response.SubjectResponse;
import java.util.List;

/**
 * Hợp đồng CRUD môn học (Subject), không bao gồm phân công giảng viên hay quản lý tài liệu.
 */
public interface SubjectService {

    /**
     * Tạo môn học mới; kiểm tra trùng tên và mã môn trước khi lưu.
     */
    SubjectResponse createSubject(SubjectCreationRequest request);

    /**
     * Lấy toàn bộ danh sách môn học hiện có; mọi role đều đọc được.
     */
    List<SubjectResponse> getAllSubjects();

    /**
     * Đọc thông tin chi tiết một môn học theo id; ném {@code AppException} nếu không tìm thấy.
     */
    SubjectResponse getSubjectById(Long id);

    /**
     * Cập nhật từng phần thông tin môn học; kiểm tra trùng tên / mã môn nếu có thay đổi.
     */
    SubjectResponse updateSubject(Long id, SubjectUpdateRequest request);

    /**
     * Xóa môn học; gọi lại khi đã xóa không tạo tác dụng phụ.
     */
    void deleteSubject(Long id);
}
