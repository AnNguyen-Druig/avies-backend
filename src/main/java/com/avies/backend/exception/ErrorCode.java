package com.avies.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Uncategorized exception", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Invalid message key", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(1002, "Invalid request", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(1003, "Unauthenticated", HttpStatus.UNAUTHORIZED),      // 401
    UNAUTHORIZED(1004, "You do not have permission", HttpStatus.FORBIDDEN), // 403
    USER_EXISTED(1005, "Username already exists", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1006, "User not found", HttpStatus.NOT_FOUND),
    EMAIL_EXISTED(1007, "Email already exists", HttpStatus.BAD_REQUEST),
    ROLE_NOT_EXISTED(1008, "Role does not exist", HttpStatus.NOT_FOUND),
    PASSWORD_TOO_SHORT(1009, "Password must be at least 6 characters", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1010, "Email is invalid", HttpStatus.BAD_REQUEST),
    USERNAME_REQUIRED(1011, "Username is required", HttpStatus.BAD_REQUEST),
    PASSWORD_REQUIRED(1012, "Password is required", HttpStatus.BAD_REQUEST),
    FULLNAME_REQUIRED(1013, "Full name is required", HttpStatus.BAD_REQUEST),
    TOKEN_INVALID(1014, "Token is invalid or expired", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(1015, "Username or Password is incorrect", HttpStatus.UNAUTHORIZED),
    SUBJECT_NOT_EXISTED(1016, "Subject not found", HttpStatus.NOT_FOUND),
    FILE_EMPTY(1017, "Uploaded file cannot be empty", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_FILE_TYPE(1018, "Unsupported file format. Please upload PDF, DOCX, PPTX, or TXT", HttpStatus.BAD_REQUEST),
    FILE_PARSING_FAILED(1019, "Failed to parse content from file", HttpStatus.INTERNAL_SERVER_ERROR),
    OPENAI_API_ERROR(1020, "Failed to generate embeddings via AI service", HttpStatus.INTERNAL_SERVER_ERROR),
    MATERIAL_NOT_EXISTED(1021, "Learning material not found", HttpStatus.NOT_FOUND),
    SUBJECT_EXISTED(1022, "Subject code already exists", HttpStatus.BAD_REQUEST),
    ROLE_NOT_ALLOWED(1023, "Only STUDENT and LECTURER roles are allowed for registration", HttpStatus.BAD_REQUEST),
    NO_RELEVANT_MATERIALS(1024, "Môn học chưa có tài liệu học tập sẵn sàng để sinh câu hỏi", HttpStatus.BAD_REQUEST),
    AI_GENERATION_FAILED(1025, "Không thể sinh câu hỏi từ dịch vụ AI", HttpStatus.INTERNAL_SERVER_ERROR),
    /** Câu hỏi không tồn tại hoặc không nằm trong phạm vi được xem. */
    QUESTION_NOT_FOUND(1100, "Không tìm thấy câu hỏi", HttpStatus.NOT_FOUND),
    SUBJECT_NOT_ASSIGNED(1101, "Bạn chưa được phân công môn học này", HttpStatus.FORBIDDEN),
    QUESTION_NOT_EDITABLE(1102, "Chỉ được sửa hoặc xóa bản nháp chưa lưu trữ", HttpStatus.CONFLICT),
    QUESTION_IN_USE(1103, "Câu hỏi đã được sử dụng trong bài thi", HttpStatus.CONFLICT),
    QUESTION_VERSION_CONFLICT(1104, "Câu hỏi đã thay đổi; hãy tải lại trước khi thao tác", HttpStatus.CONFLICT),
    QUESTION_DATA_CONFLICT(1105, "Dữ liệu đang được tham chiếu hoặc có thao tác đồng thời", HttpStatus.CONFLICT),
    QUESTION_ALREADY_APPROVED(1106, "Câu hỏi này đã được duyệt trước đó", HttpStatus.BAD_REQUEST),
    /** Tài khoản đích tồn tại nhưng không phải giảng viên. */
    ASSIGNMENT_NOT_LECTURER(1200, "Tài khoản được chọn không có vai trò LECTURER", HttpStatus.BAD_REQUEST),
    /** Phân công gặp thay đổi đồng thời hoặc dữ liệu tham chiếu không còn hợp lệ. */
    ASSIGNMENT_CONFLICT(1201, "Phân công đang có thay đổi đồng thời; hãy tải lại và thử lại", HttpStatus.CONFLICT),
    /** Phiên không tồn tại hoặc không thuộc quyền sở hữu được xem. */
    EXAM_SESSION_NOT_FOUND(1300, "Không tìm thấy phiên thi", HttpStatus.NOT_FOUND),
    /** Chỉ phiên UPCOMING mới có thể sửa hoặc xóa. */
    EXAM_SESSION_NOT_EDITABLE(1301, "Chỉ được sửa hoặc xóa phiên thi UPCOMING", HttpStatus.CONFLICT),
    /** Bài thi đã gắn hoặc bắt đầu khiến thao tác thay đổi không còn an toàn. */
    EXAM_SESSION_IN_USE(1302, "Phiên thi đã có bài thi liên quan không cho phép thao tác này", HttpStatus.CONFLICT),
    /** Khóa hoặc dữ liệu phiên thi gặp xung đột; không lộ SQL cho client. */
    EXAM_SESSION_CONFLICT(1303, "Phiên thi hoặc phân công đang thay đổi; hãy tải lại và thử lại", HttpStatus.CONFLICT),
    /** Trạng thái cuối sau khi gộp PATCH có lịch hoặc giới hạn không hợp lệ. */
    EXAM_SESSION_INVALID_SCHEDULE(1304, "Lịch thi phải đầy đủ, bắt đầu trong tương lai, kết thúc sau bắt đầu, tổng thời gian phiên không được nhỏ hơn thời gian mỗi sinh viên và các giới hạn phải hợp lệ", HttpStatus.BAD_REQUEST),
    /** Môn phải có rubric đầy đủ/hợp lệ trước khi cho phép tạo phiên mới. */
    EXAM_SESSION_RUBRIC_REQUIRED(1305, "Bạn phải tạo đầy đủ Rubrics cho môn", HttpStatus.BAD_REQUEST),
    /** Thiếu câu hỏi chính hợp lệ khi tạo phiên; số tối thiểu lấy từ cấu hình Main. */
    EXAM_SESSION_QUESTIONS_REQUIRED(1306, "Bạn phải tạo đủ %d số câu hỏi chính đã được APPROVED", HttpStatus.BAD_REQUEST),
    /** Bài thi không tồn tại hoặc không nằm trong quyền sở hữu được thao tác. */
    STUDENT_EXAM_NOT_FOUND(1400, "Không tìm thấy bài thi sinh viên", HttpStatus.NOT_FOUND),
    /** User đích đã tồn tại nhưng hiện không phải sinh viên. */
    ASSIGNMENT_NOT_STUDENT(1401, "Tài khoản được chọn không có vai trò STUDENT", HttpStatus.BAD_REQUEST),
    /** Không có đủ slot nguyên vẹn trong cửa sổ phiên cho toàn bộ batch mới. */
    EXAM_SLOT_CAPACITY_EXCEEDED(1402, "Phiên thi không còn đủ slot cho toàn bộ yêu cầu", HttpStatus.CONFLICT),
    /** Lịch chưa xác định, đã qua hoặc dữ liệu legacy không thể xếp thêm an toàn. */
    EXAM_SCHEDULE_UNAVAILABLE(1403, "Lịch phiên hoặc bài thi cũ chưa hợp lệ để gán thêm sinh viên", HttpStatus.CONFLICT),
    /** Không được xóa bài đã có câu hỏi, thời gian thực tế, điểm hoặc bằng chứng thi. */
    STUDENT_EXAM_IN_USE(1404, "Bài thi đã được sử dụng hoặc có dữ liệu cần bảo toàn", HttpStatus.CONFLICT),
    /** Không đổi cửa sổ lịch hoặc thời lượng slot khi phiên còn bất kỳ StudentExam nào. */
    EXAM_SESSION_SCHEDULE_LOCKED(1405, "Không được đổi lịch phiên khi đã có sinh viên được gán", HttpStatus.CONFLICT),
    /** Không có đủ câu hợp lệ distinct, không giảm số lượng hoặc sinh AI thay thế. */
    EXAM_QUESTION_POOL_INSUFFICIENT(1500, "Không đủ câu hỏi hợp lệ để chuẩn bị bài thi", HttpStatus.CONFLICT),
    /** Các rubric chưa đáp ứng quy tắc chấm điểm để lập bộ đầy đủ. */
    EXAM_QUESTION_RUBRIC_INVALID(1501, "Rubric chưa hợp lệ để chuẩn bị đầy đủ bộ câu hỏi", HttpStatus.CONFLICT),
    /** Bộ đã lưu thiếu/thừa, trùng hoặc có thứ tự không liên tục; cần kiểm tra dữ liệu. */
    EXAM_QUESTION_SET_INCONSISTENT(1502, "Bộ câu hỏi đã lưu không nhất quán; cần kiểm tra dữ liệu", HttpStatus.CONFLICT),
    /** Câu hoặc rubric đổi sau khi đọc pool và trước khi hoàn tất lấy khóa. */
    EXAM_QUESTION_ELIGIBILITY_CHANGED(1503, "Dữ liệu câu hỏi đã thay đổi; vui lòng thử lại", HttpStatus.CONFLICT),
    /** Không tạo bộ mới cho bài đã bắt đầu hoặc phiên đã hoàn tất. */
    EXAM_QUESTION_SELECTION_NOT_ALLOWED(1504, "Không thể chuẩn bị bộ câu hỏi với trạng thái hiện tại", HttpStatus.CONFLICT),
    /** Lỗi khóa/ràng buộc chỉ trả mã an toàn, không lộ SQL hoặc dữ liệu chấm thi. */
    EXAM_QUESTION_SELECTION_CONFLICT(1505, "Dữ liệu đang thay đổi; vui lòng tải lại và thử lại", HttpStatus.CONFLICT),
    /** Thiếu hoặc hỏng bản chụp lịch sử; không được backfill âm thầm từ nguồn hiện hành. */
    EXAM_SNAPSHOT_UNAVAILABLE(1506, "Bản chụp bài thi thiếu hoặc không hợp lệ; cần kiểm tra dữ liệu", HttpStatus.CONFLICT),
    /** Lượt thi không ở trạng thái cho phép bắt đầu hoặc hoàn thành. */
    EXAM_LIFECYCLE_NOT_ALLOWED(1700, "Trạng thái bài thi không cho phép thực hiện thao tác này", HttpStatus.CONFLICT),
    /** Thiếu lịch hoặc lịch lượt thi không khớp lưới slot của phiên. */
    EXAM_LIFECYCLE_INVALID_SCHEDULE(1701, "Lịch lượt thi chưa hợp lệ; vui lòng liên hệ giảng viên", HttpStatus.CONFLICT),
    /** Đồng hồ máy chủ quyết định cửa sổ thi, không tin thời gian từ trình duyệt. */
    EXAM_OUTSIDE_SLOT(1702, "Chưa đến giờ thi hoặc đã hết khung giờ được phân công", HttpStatus.CONFLICT),
    /** Feature 3 chưa lưu câu trả lời hợp lệ cho đầy đủ câu hỏi chính. */
    EXAM_ANSWERS_NOT_READY(1703, "Chưa thể hoàn thành: hệ thống chưa lưu đầy đủ câu trả lời cho các câu hỏi chính", HttpStatus.CONFLICT),
    /** Trạng thái và bằng chứng thực hiện không nhất quán; không tự sửa lịch sử. */
    EXAM_LIFECYCLE_INCONSISTENT(1704, "Dữ liệu trạng thái bài thi không nhất quán; cần kiểm tra trước khi tiếp tục", HttpStatus.CONFLICT),
    /** Chỉ đóng phiên khi đã đến hoặc qua giờ kết thúc dự kiến. */
    EXAM_SESSION_NOT_ENDED(1705, "Chưa đến giờ kết thúc phiên thi nên chưa thể đóng phiên", HttpStatus.CONFLICT),
    /** Rubric không tồn tại. */
    RUBRIC_NOT_FOUND(1600, "Không tìm thấy rubric", HttpStatus.NOT_FOUND),
    /** Môn học đã có rubric. */
    RUBRIC_EXISTED(1601, "Môn học này đã có rubric", HttpStatus.CONFLICT),
    /** Tiêu chí rubric không hợp lệ (tổng điểm != 10.00). */
    RUBRIC_CRITERIA_INVALID(1602, "Tổng điểm các tiêu chí rubric phải bằng 10.00", HttpStatus.BAD_REQUEST),
    /** Rubric chưa có tiêu chí nào. */
    RUBRIC_CRITERIA_EMPTY(1603, "Rubric phải có ít nhất một tiêu chí đánh giá", HttpStatus.BAD_REQUEST),
    /** Rubric đang được dùng không được xóa. */
    RUBRIC_IN_USE(1604, "Rubric đang được sử dụng trong kỳ thi, không thể xóa", HttpStatus.CONFLICT),
    /** Chưa mở hội thoại cho lượt thi này. */
    ORAL_PROGRESS_NOT_FOUND(1800, "Chưa có tiến trình thi vấn đáp", HttpStatus.NOT_FOUND),
    /** Lệnh không đúng bước hoặc không đúng lượt hỏi hiện hành. */
    ORAL_ACTION_NOT_ALLOWED(1801, "Thao tác không phù hợp với bước thi hiện tại", HttpStatus.CONFLICT),
    /** Tab đang giữ dữ liệu hoặc requestId không còn khớp yêu cầu. */
    ORAL_STALE_COMMAND(1802, "Tiến trình đã thay đổi; hãy tải lại trước khi thao tác", HttpStatus.CONFLICT),
    /** Chỉ một tab được điều khiển trong thời gian lease còn hiệu lực. */
    ORAL_ACTIVE_CLIENT(1803, "Lượt thi đang được điều khiển ở tab khác; hãy đóng tab đó hoặc chờ quyền tương tác hết hạn", HttpStatus.CONFLICT),
    /** Không ghép transcript cũ vào hội thoại mới để giả làm lịch sử. */
    ORAL_LEGACY_EVIDENCE(1804, "Lượt thi đã có hội thoại cũ; cần kiểm tra trước khi mở tiến trình mới", HttpStatus.CONFLICT),
    /** Callback nhà cung cấp phải đúng mã công việc, ngôn ngữ và nội dung hợp lệ. */
    ORAL_PROVIDER_RESULT_INVALID(1805, "Kết quả xử lý giọng nói hoặc hội thoại chưa hợp lệ", HttpStatus.CONFLICT),
    /** Code có thể triển khai trước migration mà API cũ vẫn hoạt động. */
    ORAL_SCHEMA_NOT_READY(1806, "Dữ liệu thi vấn đáp chưa được nâng cấp; vui lòng liên hệ người quản lý hệ thống", HttpStatus.SERVICE_UNAVAILABLE),
    /** TTS chưa bật hoặc cấu hình/key chưa được dịch vụ chấp nhận. */
    ORAL_SPEECH_NOT_READY(1807, "Giọng đọc chưa sẵn sàng; vui lòng kiểm tra cấu hình dịch vụ TTS", HttpStatus.SERVICE_UNAVAILABLE),
    /** Mạng, timeout hoặc audio không đúng định dạng; không tính là Student trả lời sai. */
    ORAL_SPEECH_FAILED(1808, "Chưa tạo được giọng đọc; hãy thử lại lượt hiện tại", HttpStatus.BAD_GATEWAY),
    /** Quota dịch vụ hoặc số lần tổng hợp đồng thời đã đạt giới hạn. */
    ORAL_SPEECH_QUOTA(1809, "Dịch vụ giọng đọc đang bận hoặc đã hết hạn mức; vui lòng thử lại sau", HttpStatus.TOO_MANY_REQUESTS),
    /** STT bị tắt hoặc key/cấu hình bị từ chối. */
    ORAL_TRANSCRIPTION_NOT_READY(1810, "Nhận dạng giọng nói chưa sẵn sàng; vui lòng kiểm tra cấu hình Groq", HttpStatus.SERVICE_UNAVAILABLE),
    /** Không biến lỗi kỹ thuật thành câu trả lời rỗng hoặc điểm không. */
    ORAL_TRANSCRIPTION_FAILED(1811, "Chưa nhận dạng được câu trả lời; vui lòng thử lại lượt xử lý", HttpStatus.BAD_GATEWAY),
    /** Không tự retry liên tục khi đã hết quota. */
    ORAL_TRANSCRIPTION_QUOTA(1812, "Dịch vụ nhận dạng đang bận hoặc đã hết hạn mức; vui lòng thử lại sau", HttpStatus.TOO_MANY_REQUESTS),
    /** File rỗng/quá lớn/định dạng không được hỗ trợ. */
    ORAL_AUDIO_INVALID(1813, "Bản ghi âm không hợp lệ; hãy ghi lại bằng micro của trình duyệt", HttpStatus.BAD_REQUEST),
    /** Submission đã gắn với nội dung âm thanh khác. */
    ORAL_AUDIO_CONFLICT(1814, "Bản ghi của lượt xử lý đã được chốt; không thể thay bằng bản ghi khác", HttpStatus.CONFLICT),
    /** AI bị tắt hoặc key/model chưa hợp lệ. */
    ORAL_DECISION_NOT_READY(1815, "AI hội thoại chưa sẵn sàng; vui lòng kiểm tra cấu hình Gemini", HttpStatus.SERVICE_UNAVAILABLE),
    /** Không dùng lỗi AI như một quyết định chuyển câu hoặc điểm không. */
    ORAL_DECISION_FAILED(1816, "Chưa quyết định được lượt hỏi tiếp theo; vui lòng thử lại", HttpStatus.BAD_GATEWAY),
    /** Quota AI được tách khỏi quota giọng đọc và STT. */
    ORAL_DECISION_QUOTA(1817, "AI hội thoại đang bận hoặc đã hết hạn mức; vui lòng thử lại sau", HttpStatus.TOO_MANY_REQUESTS);


    private final int code;
    private final String message;
    private final HttpStatus httpStatusCode;

    ErrorCode(int code, String message, HttpStatus httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }
}
