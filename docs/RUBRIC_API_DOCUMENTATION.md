# Tài Liệu API CRUD Rubric (Tiêu Chí Chấm Điểm Theo Môn Học)

> **Dành cho Frontend Team (FE)**  
> **Phiên bản:** 1.0  
> **Base URL:** `http://localhost:8080` (hoặc cấu hình từ biến môi trường `VITE_API_URL` / `NEXT_PUBLIC_API_URL`)  
> **Prefix:** `/rubrics`  
> **Xác thực:** Bắt buộc gửi JWT qua Header `Authorization: Bearer <accessToken>`  
> **Quyền truy cập:** `ROLE_ADMIN` hoặc `ROLE_LECTURER` (giảng viên phải được phân công môn học này).

---

## 📌 Các Quy Tắc Nghiệp Vụ (Business Rules) FE Cần Lưu Ý Khi Làm UI

1. **Mối quan hệ 1 - 1 với Môn học**:
   - Mỗi môn học chỉ có **duy nhất 1 bộ Rubric**.
   - Khi môn học chưa có Rubric, gọi `GET /rubrics/subject/{subjectId}` sẽ trả về lỗi `1600 - RUBRIC_NOT_FOUND`. Lúc này FE nên hiển thị trạng thái Empty State kèm nút **"Thiết lập Rubric mới"**.
   - Nếu môn học đã có Rubric, cố tình gọi `POST /rubrics` sẽ trả về lỗi `1601 - RUBRIC_EXISTED`. Lúc này người dùng cần dùng tính năng **"Chỉnh sửa Rubric"** (`PUT /rubrics/{id}`).

2. **Quy tắc tính điểm (Rất quan trọng cho Form validation)**:
   - Tổng điểm trọng số (`pointWeight`) của tất cả các tiêu chí con bắt buộc phải **bằng đúng 10.00**.
   - Mỗi tiêu chí phải có `pointWeight > 0`.
   - *Khuyến nghị UI:* FE nên làm một thanh hiển thị tổng điểm hiện tại theo thời gian thực (real-time progress bar/badge):
     - Ví dụ: `Tổng điểm: 7.50 / 10.00` (màu vàng/đỏ, disable nút Lưu).
     - Khi tròn `10.00 / 10.00` thì đổi sang màu xanh và enable nút Lưu.

3. **Thứ tự hiển thị (`displayOrder`)**:
   - Nếu FE hỗ trợ kéo thả (Drag & Drop), gửi kèm `displayOrder: 1, 2, 3...` tương ứng. Nếu không gửi, Backend sẽ tự động đánh số tăng dần.

---

## 📋 Danh Sách Các API

| STT | Phương thức | Endpoint | Mô tả |
| :---: | :---: | :--- | :--- |
| 1 | `POST` | `/rubrics` | Tạo mới bộ Rubric cho môn học |
| 2 | `GET` | `/rubrics/subject/{subjectId}` | Lấy Rubric theo ID môn học *(Khuyên dùng)* |
| 3 | `GET` | `/rubrics/{id}` | Lấy chi tiết Rubric theo ID của Rubric |
| 4 | `PUT` | `/rubrics/{id}` | Cập nhật thông tin và danh sách tiêu chí Rubric |
| 5 | `DELETE` | `/rubrics/{id}` | Xóa Rubric của môn học |

---

## 1. Tạo Mới Rubric Cho Môn Học

- **Endpoint:** `POST /rubrics`
- **Headers:**
  ```http
  Content-Type: application/json
  Authorization: Bearer <accessToken>
  ```

### Request Body
```json
{
  "subjectId": 1,
  "title": "Rubric đánh giá vấn đáp môn Kiến trúc và Thiết kế phần mềm (SWD392)",
  "description": "Tiêu chí đánh giá chuẩn vấn đáp: Nắm vững kiến trúc hệ thống, mẫu thiết kế và giải trình giải pháp.",
  "criteria": [
    {
      "criterionText": "Hiểu biết về Nguyên lý Kiến trúc & Design Patterns (SOLID, GoF)",
      "pointWeight": 3.50,
      "isMandatory": true,
      "displayOrder": 1
    },
    {
      "criterionText": "Thiết kế & Phân rã hệ thống (Vẽ và giải thích đúng các sơ đồ UML)",
      "pointWeight": 3.50,
      "isMandatory": false,
      "displayOrder": 2
    },
    {
      "criterionText": "Đánh giá chất lượng phi chức năng (Scalability, Security, Maintainability)",
      "pointWeight": 2.00,
      "isMandatory": false,
      "displayOrder": 3
    },
    {
      "criterionText": "Kỹ năng trình bày và phản biện kiến trúc",
      "pointWeight": 1.00,
      "isMandatory": false,
      "displayOrder": 4
    }
  ]
}
```

### Response Thành Công (`201 Created`)
```json
{
  "code": 1000,
  "message": "Tạo Rubric thành công",
  "result": {
    "id": 1,
    "subjectId": 1,
    "subjectCode": "SWD392",
    "subjectName": "Software Architecture and Design",
    "title": "Rubric đánh giá vấn đáp môn Kiến trúc và Thiết kế phần mềm (SWD392)",
    "description": "Tiêu chí đánh giá chuẩn vấn đáp: Nắm vững kiến trúc hệ thống, mẫu thiết kế và giải trình giải pháp.",
    "criteria": [
      {
        "id": 1,
        "criterionText": "Hiểu biết về Nguyên lý Kiến trúc & Design Patterns (SOLID, GoF)",
        "pointWeight": 3.50,
        "isMandatory": true,
        "displayOrder": 1
      },
      {
        "id": 2,
        "criterionText": "Thiết kế & Phân rã hệ thống (Vẽ và giải thích đúng các sơ đồ UML)",
        "pointWeight": 3.50,
        "isMandatory": false,
        "displayOrder": 2
      },
      {
        "id": 3,
        "criterionText": "Đánh giá chất lượng phi chức năng (Scalability, Security, Maintainability)",
        "pointWeight": 2.00,
        "isMandatory": false,
        "displayOrder": 3
      },
      {
        "id": 4,
        "criterionText": "Kỹ năng trình bày và phản biện kiến trúc",
        "pointWeight": 1.00,
        "isMandatory": false,
        "displayOrder": 4
      }
    ],
    "createdAt": "2026-10-23T09:55:00+07:00",
    "updatedAt": "2026-10-23T09:55:00+07:00"
  }
}
```

---

## 2. Lấy Rubric Theo ID Môn Học (Khuyên dùng cho trang chi tiết Môn)

- **Endpoint:** `GET /rubrics/subject/{subjectId}`
- **Ví dụ:** `GET /rubrics/subject/1`
- **Headers:** `Authorization: Bearer <accessToken>`

### Response Thành Công (`200 OK`)
```json
{
  "code": 1000,
  "result": {
    "id": 1,
    "subjectId": 1,
    "subjectCode": "SWD392",
    "subjectName": "Software Architecture and Design",
    "title": "Rubric đánh giá vấn đáp môn Kiến trúc và Thiết kế phần mềm (SWD392)",
    "description": "Tiêu chí đánh giá chuẩn vấn đáp: Nắm vững kiến trúc hệ thống...",
    "criteria": [
      {
        "id": 1,
        "criterionText": "Hiểu biết về Nguyên lý Kiến trúc & Design Patterns (SOLID, GoF)",
        "pointWeight": 3.50,
        "isMandatory": true,
        "displayOrder": 1
      }
    ],
    "createdAt": "2026-10-23T09:55:00+07:00",
    "updatedAt": "2026-10-23T09:55:00+07:00"
  }
}
```

> **Lưu ý FE:** Nếu API trả về mã lỗi `1600` (HTTP 404), nghĩa là môn học này chưa có Rubric. FE không xem đây là lỗi sập app mà hãy hiển thị Empty State "Môn học chưa thiết lập Rubric chấm điểm" và gợi ý nút "Tạo Rubric".

---

## 3. Lấy Chi Tiết Rubric Theo ID

- **Endpoint:** `GET /rubrics/{id}`
- **Ví dụ:** `GET /rubrics/1`
- **Headers:** `Authorization: Bearer <accessToken>`

### Response Thành Công (`200 OK`)
Cấu trúc trả về giống mục 2.

---

## 4. Cập Nhật Rubric & Danh Sách Tiêu Chí

- **Endpoint:** `PUT /rubrics/{id}`
- **Ví dụ:** `PUT /rubrics/1`
- **Headers:**
  ```http
  Content-Type: application/json
  Authorization: Bearer <accessToken>
  ```

### Request Body
> **Lưu ý linh hoạt cho FE:**
> - Trường `criteria` là **không bắt buộc (optional)**.
> - **Trường hợp 1 (Sửa nhanh Title / Description):** Không cần truyền `criteria`, Backend sẽ **giữ nguyên toàn bộ tiêu chí cũ**.
> - **Trường hợp 2 (Sửa sâu tiêu chí):** Truyền mảng `criteria` mới, Backend sẽ validate tổng điểm = 10.00 và đồng bộ lại danh sách tiêu chí.

#### Ví dụ 1: Chỉ sửa Title và/hoặc Description (Tiêu chí giữ nguyên)
```json
{
  "title": "Rubric đánh giá vấn đáp môn SWD392 (Kỳ Fall 2026)",
  "description": "Điều chỉnh mô tả yêu cầu môn học"
}
```

#### Ví dụ 2: Cập nhật toàn bộ cả tiêu chí (Tổng điểm các tiêu chí mới bắt buộc = 10.00)
```json
{
  "title": "Rubric đánh giá vấn đáp môn SWD392 (Đã cập nhật kỳ Fall 2026)",
  "description": "Cập nhật thang điểm tiêu chí phi chức năng",
  "criteria": [
    {
      "criterionText": "Hiểu biết về Nguyên lý Kiến trúc & Design Patterns (SOLID, GoF)",
      "pointWeight": 4.00,
      "isMandatory": true,
      "displayOrder": 1
    },
    {
      "criterionText": "Thiết kế & Phân rã hệ thống (Sơ đồ UML)",
      "pointWeight": 3.00,
      "isMandatory": false,
      "displayOrder": 2
    },
    {
      "criterionText": "Đánh giá chất lượng phi chức năng & Bảo mật",
      "pointWeight": 2.00,
      "isMandatory": false,
      "displayOrder": 3
    },
    {
      "criterionText": "Kỹ năng trình bày và trả lời phản biện",
      "pointWeight": 1.00,
      "isMandatory": false,
      "displayOrder": 4
    }
  ]
}
```

### Response Thành Công (`200 OK`)
```json
{
  "code": 1000,
  "message": "Cập nhật Rubric thành công",
  "result": {
    "id": 1,
    "subjectId": 1,
    "subjectCode": "SWD392",
    "subjectName": "Software Architecture and Design",
    "title": "Rubric đánh giá vấn đáp môn SWD392 (Đã cập nhật kỳ Fall 2026)",
    "description": "Cập nhật thang điểm tiêu chí phi chức năng",
    "criteria": [ ... ],
    "createdAt": "2026-10-23T09:55:00+07:00",
    "updatedAt": "2026-10-23T10:00:15+07:00"
  }
}
```

---

## 5. Xóa Rubric

- **Endpoint:** `DELETE /rubrics/{id}`
- **Ví dụ:** `DELETE /rubrics/1`
- **Headers:** `Authorization: Bearer <accessToken>`

### Response Thành Công (`200 OK`)
```json
{
  "code": 1000,
  "message": "Xóa Rubric thành công"
}
```

---

## ⚠️ Bảng Mã Lỗi (Error Codes) Phục Vụ Hiển Thị Toast / Thông Báo

Khi có lỗi xảy ra, Backend trả về response dạng:
```json
{
  "code": 1602,
  "message": "Tổng điểm các tiêu chí rubric phải bằng 10.00 (hiện tại: 8.5)"
}
```

| HTTP Status | Code | Message Backend | Gợi ý xử lý hiển thị trên FE |
| :---: | :---: | :--- | :--- |
| **400 Bad Request** | `1602` | Tổng điểm các tiêu chí rubric phải bằng 10.00 | Hiển thị Toast cảnh báo: "Tổng điểm tiêu chí phải đủ 10.00 điểm". |
| **400 Bad Request** | `1603` | Rubric phải có ít nhất một tiêu chí đánh giá | Cảnh báo: "Vui lòng thêm ít nhất một tiêu chí". |
| **403 Forbidden** | `1101` | Bạn chưa được phân công môn học này | Thông báo không có quyền thao tác trên môn này. |
| **404 Not Found** | `1016` | Subject not found | Môn học không tồn tại. |
| **404 Not Found** | `1600` | Không tìm thấy rubric | Môn học chưa có rubric (Xử lý hiển thị Empty State). |
| **409 Conflict** | `1601` | Môn học này đã có rubric | Thông báo: "Môn học đã tồn tại Rubric, vui lòng chỉnh sửa thay vì tạo mới". |
| **409 Conflict** | `1604` | Rubric đang được sử dụng trong kỳ thi, không thể xóa | Chặn hành động xóa và thông báo cho người dùng. |

---

## 💻 TypeScript Interfaces (Copy Dùng Trực Tiếp Trong Codebase FE)

```typescript
export interface RubricCriterionRequest {
  id?: number;
  criterionText: string;
  pointWeight: number; // Decimal (0.01 - 10.00)
  isMandatory?: boolean;
  displayOrder?: number;
}

export interface RubricCriterionResponse {
  id: number;
  criterionText: string;
  pointWeight: number;
  isMandatory: boolean;
  displayOrder: number;
}

export interface RubricCreationRequest {
  subjectId: number;
  title?: string;
  description?: string;
  criteria: RubricCriterionRequest[];
}

export interface RubricUpdateRequest {
  title?: string;
  description?: string;
  criteria: RubricCriterionRequest[];
}

export interface RubricResponse {
  id: number;
  subjectId: number;
  subjectCode: string;
  subjectName: string;
  title: string;
  description: string;
  criteria: RubricCriterionResponse[];
  createdAt: string;
  updatedAt: string | null;
}

export interface ApiResponse<T> {
  code: number;
  message?: string;
  result?: T;
}
```
