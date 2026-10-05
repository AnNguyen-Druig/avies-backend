package com.avies.backend.service;

import com.avies.backend.dto.request.SubjectCreationRequest;
import com.avies.backend.dto.request.SubjectUpdateRequest;
import com.avies.backend.dto.response.SubjectResponse;
import java.util.List;

public interface SubjectService {

    SubjectResponse createSubject(SubjectCreationRequest request);

    List<SubjectResponse> getAllSubjects();

    SubjectResponse getSubjectById(Long id);

    SubjectResponse updateSubject(Long id, SubjectUpdateRequest request);

    void deleteSubject(Long id);
}
