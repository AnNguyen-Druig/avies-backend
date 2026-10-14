# 📘 TÀI LIỆU TÍCH HỢP API: SINH CÂU HỎI VẤN ĐÁP & RUBRIC BẰNG AI (RAG)

> **Dành cho:** Frontend Team  
> **Phiên bản:** v1.0  
> **Chức năng:** Tự động tạo câu hỏi vấn đáp và tiêu chí chấm điểm (Rubric thang 10) từ tài liệu môn học qua AI & pgvector Cosine Distance.

---

## 1. Thông Tin Chung

- **Endpoint:** `/questions/generate`
- **Method:** `POST`
- **Content-Type:** `application/json`
- **Yêu cầu Xác thực:** Bắt buộc Bearer Token trong Header:
  ```http
  Authorization: Bearer <access_token>
  ```
- **Quyền hạn truy cập (Authorization):**
  - **`ROLE_ADMIN`**: Có quyền sinh câu hỏi cho tất cả các môn học.
  - **`ROLE_LECTURER`**: Giảng viên **bắt buộc phải đã được phân công** phụ trách môn học đó trong hệ thống (nếu chưa phân công sẽ bị từ chối với mã lỗi `1101`).

> ⚠️ **LƯU Ý QUAN TRỌNG:**  
> Môn học (`subjectId`) bắt buộc phải **đã được upload tài liệu thành công trước đó** (tài liệu ở trạng thái `READY` và đã được cắt đoạn/trích xuất vector). Nếu môn học chưa có tài liệu, API sẽ báo lỗi `1024`.

---

## 2. Request Body

### Cấu trúc JSON:
```json
{
  "subjectId": 2,
  "keyword": "Kanji JPD316",
  "bloomLevel": "UNDERSTAND",
  "count": 3,
  "materialIds": [9]
}
```

### Chi tiết các trường dữ liệu (Request Parameters):

| Tên trường | Kiểu dữ liệu | Bắt buộc | Mặc định | Mô tả & Giới hạn |
| :--- | :---: | :---: | :---: | :--- |
| `subjectId` | `Long` | **Có** | - | ID của môn học trong hệ thống (Ví dụ: `1`, `2`, `4`, `5`). |
| `keyword` | `String` | **Có** | - | Từ khóa / chủ đề bài học để tìm 5 đoạn tài liệu liên quan nhất qua Cosine Distance và làm ngữ cảnh sinh câu hỏi. |
| `bloomLevel` | `String` | Không | `null` | Mức độ nhận thức theo thang Bloom. Chỉ chấp nhận **1 trong 6 giá trị**:<br>• `REMEMBER` (Ghi nhớ)<br>• `UNDERSTAND` (Thông hiểu)<br>• `APPLY` (Vận dụng)<br>• `ANALYZE` (Phân tích)<br>• `EVALUATE` (Đánh giá)<br>• `CREATE` (Sáng tạo)<br>*(Nếu không truyền trường này, AI sẽ tự động phân tích độ phức tạp của chủ đề và gán mức độ phù hợp nhất).* |
| `count` | `Integer` | Không | `1` | Số lượng câu hỏi muốn sinh. Giá trị hợp lệ từ **1 đến 10**. |
| `materialIds`| `List<Long>`| Không | `null` | Danh sách ID tài liệu cụ thể của môn học muốn giới hạn phạm vi tìm kiếm (nếu muốn sinh từ tài liệu chỉ định). |

---

## 3. Response Dữ Liệu

### HTTP Status: `201 Created`

### Response Mẫu thành công:
```json
{
  "code": 1000,
  "message": "Sinh câu hỏi và rubric thành công.",
  "result": [
    {
      "id": 15,
      "subjectId": 2,
      "subjectName": "Intermediate Japanese 1-B1/B2",
      "content": "Dựa vào tài liệu học tập, hãy giải thích ý nghĩa cốt lõi của chữ Hán 東 (ĐÔNG) và làm rõ nét nghĩa khi kết hợp tạo thành các từ ghép chỉ thời điểm và địa điểm: 東京 (Tokyo), 東口 (Cổng đông), và 東洋 (Phương Đông)?",
      "referenceAnswer": "- Ý nghĩa cốt lõi: Chữ 東 mang nghĩa chỉ hướng đông, mặt trời mọc.\n- Từ ghép địa điểm: 東京 (thủ đô Tokyo), 東口 (lối ra phía đông của nhà ga).\n- Khái niệm mở rộng: 東洋 chỉ các nước phương Đông/Á Đông.",
      "topics": [
        "Chữ Hán JPD316",
        "Kanji JPD316"
      ],
      "bloomLevel": "UNDERSTAND",
      "answerTimeLimitSeconds": 120,
      "sourceType": "AI",
      "status": "DRAFT",
      "createdById": 6,
      "createdByName": "Nguyễn Văn Liêm",
      "updatedById": 6,
      "createdAt": "2026-10-11T03:45:10.123456Z",
      "updatedAt": "2026-10-11T03:45:10.123456Z",
      "archivedAt": null,
      "version": 0,
      "rubricId": 8,
      "rubric": {
        "id": 8,
        "questionId": 15,
        "title": "Rubric đánh giá: Ý nghĩa chữ Hán 東 (ĐÔNG)",
        "description": "Thang điểm 10 đánh giá câu trả lời vấn đáp của sinh viên.",
        "createdAt": "2026-10-11T03:45:10.123456Z",
        "criteria": [
          {
            "id": 22,
            "criterionText": "Nêu chính xác âm Hán Việt, ý nghĩa gốc và cách viết/bộ thủ của chữ 東",
            "pointWeight": 4.00,
            "isMandatory": false,
            "displayOrder": 1
          },
          {
            "id": 23,
            "criterionText": "Phân tích đúng nghĩa ngữ cảnh của các từ ghép 東京, 東口, 東洋",
            "pointWeight": 4.00,
            "isMandatory": false,
            "displayOrder": 2
          },
          {
            "id": 24,
            "criterionText": "Kỹ năng phát âm tiếng Nhật chuẩn và diễn đạt mạch lạc, tự tin",
            "pointWeight": 2.00,
            "isMandatory": false,
            "displayOrder": 3
          }
        ]
      }
    }
  ]
}
```

---

## 4. Ý Nghĩa Chi Tiết Các Trường Response

### 4.1. Cấu trúc Response chuẩn:
- `code` (`Integer`): Mã phản hồi nghiệp vụ (`1000` là thành công).
- `message` (`String`): Thông báo kết quả thao tác.
- `result` (`Array`): Danh sách các câu hỏi vừa được sinh ra và lưu vào ngân hàng câu hỏi.

### 4.2. Thông tin Câu hỏi (`Question`):
- `id`: Khóa chính định danh của câu hỏi trong ngân hàng.
- `subjectId`, `subjectName`: ID và tên môn học tương ứng.
- `content`: Nội dung câu hỏi vấn đáp (Oral exam question).
- `referenceAnswer`: Đáp án chuẩn / dàn ý chi tiết để giảng viên đối chiếu khi vấn đáp.
- `topics`: Mảng danh sách nhãn/chủ đề liên quan của câu hỏi.
- `bloomLevel`: Mức nhận thức Bloom (`REMEMBER`, `UNDERSTAND`, `APPLY`, `ANALYZE`, `EVALUATE`, `CREATE`).
- `answerTimeLimitSeconds`: Thời gian dự kiến cho sinh viên trả lời (tính bằng giây, ví dụ `120s`, `180s`).
- `sourceType`: Nguồn tạo (`AI`).
- `status`: Trạng thái câu hỏi. Luôn là **`DRAFT`** (Bản nháp) để giảng viên có thể kiểm tra, chỉnh sửa hoặc duyệt trước khi đưa vào đề thi.
- `createdById`, `createdByName`: ID và họ tên của giảng viên thực hiện thao tác tạo.
- `updatedById`: ID người cập nhật gần nhất.
- `createdAt`, `updatedAt`: Thời điểm tạo và thời điểm cập nhật (chuẩn ISO 8601 UTC).
- `archivedAt`: Thời điểm lưu trữ/vô hiệu hóa (`null` là câu hỏi đang hoạt động bình thường).
- `version`: Số phiên bản dùng cho cơ chế khóa lạc quan (Optimistic Locking).
- `rubricId`: ID của Rubric gắn liền với câu hỏi.

### 4.3. Thông tin Rubric & Tiêu chí chấm điểm (`rubric` & `criteria`):
- `rubric.id`: ID định danh của bảng rubric.
- `rubric.questionId`: ID câu hỏi tương ứng (quan hệ 1 - 1).
- `rubric.title`: Tiêu đề hướng dẫn của rubric.
- `rubric.description`: Mô tả quy tắc chấm điểm tổng quát.
- `rubric.criteria`: Danh sách các tiêu chí chấm điểm thành phần:
  - `criterion.id`: ID định danh của tiêu chí.
  - `criterion.criterionText`: Nội dung chi tiết của tiêu chí cần đánh giá.
  - `criterion.pointWeight`: Trọng số điểm của tiêu chí. **Tổng `pointWeight` của tất cả tiêu chí luôn bằng chính xác `10.00` điểm**.
  - `criterion.isMandatory`: Cờ bắt buộc (`true`/`false`).
  - `criterion.displayOrder`: Thứ tự hiển thị của tiêu chí khi chấm thi (1, 2, 3...).

---

## 5. Bảng Mã Lỗi (Error Codes) Thường Gặp

Khi có lỗi xảy ra, server sẽ trả về HTTP status tương ứng kèm cấu trúc:
```json
{
  "code": 1002,
  "message": "Thông báo lỗi chi tiết"
}
```

| HTTP Status | Mã Code | Tên lỗi | Nguyên nhân & Hướng xử lý phía Frontend |
| :---: | :---: | :--- | :--- |
| `400 Bad Request` | **1002** | `INVALID_REQUEST` | **1.** Dữ liệu đầu vào không hợp lệ.<br>**2.** Nhập sai `bloomLevel` (ví dụ gửi `"HAHA"`). Hệ thống sẽ báo cụ thể: *"Mức độ nhận thức (bloomLevel) không hợp lệ; bloomLevel phải nằm trong 6 level: REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE và CREATE."*<br>**3.** Số lượng `count <= 0`. |
| `400 Bad Request` | **1024** | `NO_RELEVANT_MATERIALS` | Môn học **chưa có tài liệu** hoặc các tài liệu chưa sẵn sàng (`READY`). Cần nhắc người dùng upload tài liệu cho môn học trước. |
| `401 Unauthorized` | **1003** | `UNAUTHENTICATED` | Chưa truyền Bearer Token hoặc token đã hết hạn / không hợp lệ. Điều hướng người dùng về màn hình Login. |
| `403 Forbidden` | **1004** | `UNAUTHORIZED` | Tài khoản đăng nhập không có quyền `ROLE_LECTURER` hoặc `ROLE_ADMIN` (ví dụ: tài khoản `STUDENT`). |
| `403 Forbidden` | **1101** | `SUBJECT_NOT_ASSIGNED` | Giảng viên **chưa được phân công phụ trách môn học này**. Chỉ Admin hoặc giảng viên được phân công mới có quyền tạo câu hỏi. |
| `404 Not Found` | **1016** | `SUBJECT_NOT_EXISTED` | `subjectId` gửi lên không tồn tại trong hệ thống. |
| `500 Internal Error` | **1025** | `AI_GENERATION_FAILED` | Không thể gọi dịch vụ AI (OpenAI / Gemini) do lỗi mạng hoặc quota. FE hiển thị popup thông báo và nút *"Thử lại"*. |
| `500 Internal Error` | **9999** | `UNCATEGORIZED_EXCEPTION` | Lỗi máy chủ không xác định. |

---

## 6. Gợi Ý Xử Lý Giao Diện (Frontend Flow)

1. **Combobox chọn Mức độ Bloom (`bloomLevel`):**
   - Đặt 6 lựa chọn:
     - `REMEMBER` (Nhớ)
     - `UNDERSTAND` (Hiểu)
     - `APPLY` (Vận dụng)
     - `ANALYZE` (Phân tích)
     - `EVALUATE` (Đánh giá)
     - `CREATE` (Sáng tạo)
   - Có thêm mục *"Tự động (AI đề xuất)"* (khi chọn mục này thì **không gửi** trường `bloomLevel` trong request body).
2. **Input Số lượng câu hỏi (`count`):**
   - Giới hạn min = `1`, max = `10` (khuyên dùng `1` - `5` câu để thời gian phản hồi AI nhanh nhất).
3. **Hiển thị sau khi sinh thành công:**
   - Hiển thị danh sách câu hỏi ở dạng bản nháp (`DRAFT`).
   - Hiển thị Rubric bên dưới mỗi câu hỏi với các tiêu chí và điểm thành phần để giảng viên duyệt và có thể chỉnh sửa trước khi chính thức đưa vào ngân hàng đề thi.
