# Feature 2 / P0 — Question Selection & Persistence

Ngày triển khai: 19/10/2026. Baseline đã xác minh: nhánh `dev/An`, HEAD `825cc50`, worktree sạch trước khi code.

## 1. Phạm vi và hợp đồng

Bổ sung service nội bộ nối ngân hàng câu hỏi với bài thi sinh viên, tái sử dụng đúng bảng student_exam_questions. Không có REST endpoint mới, không tự gán câu qua API STUDENT, không sửa frontend hoặc schema/migration.

```java
List<SelectedMainQuestion> selectForStart(Long studentExamId);
```

Interface: ExamQuestionSelectionService; implementation: ExamQuestionSelectionServiceImpl.

- Phương thức chỉ được gọi từ workflow tin cậy có transaction ghi đang mở, dùng Propagation.MANDATORY. Không tự commit bộ câu trước Start.
- Service này KHÔNG xác thực JWT hoặc cấp quyền bắt đầu thi thay caller. Không nối trực tiếp nó vào controller cho STUDENT.
- Future Start phải kiểm tra principal, role DB/JWT hiện hành, ownership của sinh viên, quy tắc lecturer-subject assignment và thời gian được phép bắt đầu trước khi dùng kết quả.
- Kết quả chỉ gồm studentExamQuestionId, questionId và questionOrder, không có content, referenceAnswer, rubric hoặc điểm.
- Không cập nhật StudentExam.status, startedAt, completedAt, ExamSession.status hoặc lịch slot; không tạo câu AI, gọi AI, STT/TTS hoặc WebSocket.

Ví dụ kết quả nội bộ, không phải response API mới:

```json
[
  {"studentExamQuestionId": 501, "questionId": 12, "questionOrder": 1},
  {"studentExamQuestionId": 502, "questionId": 9, "questionOrder": 2},
  {"studentExamQuestionId": 503, "questionId": 20, "questionOrder": 3}
]
```

ID chỉ minh họa. Không trả nguyên danh sách này tới student UI hoặc dùng nó làm bằng chứng đã được cấp quyền Start.

## 2. Điều kiện sử dụng câu và rubric

Nguồn chính thức là Question.status, không suy diễn từ cờ legacy isApproved.

Một câu mới được chọn khi:

- Đúng ExamSession.subject.
- status=APPROVED, archivedAt=null.
- Bloom có tên thuộc enum BloomLevel hiện hành.
- Nội dung không trắng/NUL, tối đa 10.000 ký tự; version tồn tại và không âm.
- Có rubric riêng và ít nhất một tiêu chí sử dụng được.
- Mỗi tiêu chí có text không trắng/NUL, điểm tối đa dương và không quá 10, giá trị có tối đa 2 chữ số thập phân.
- Tổng trọng số tiêu chí đúng 10 bằng BigDecimal, không dùng double hoặc tự làm tròn để chấp nhận.
- displayOrder dương, duy nhất; ID tiêu chí duy nhất. Không bắt buộc thứ tự liên tục vì constraint hiện hành chỉ yêu cầu dương/unique.
- isMandatory phải có giá trị; cờ được giữ nguyên, selection không áp dụng quy tắc tự trượt cả câu.

Rubric/title/description không bị tự tạo, chuẩn hóa, sửa trọng số hoặc thêm tiêu chí. Reference answer vẫn tùy chọn như Feature 1. Kiểm tra này không sửa approval workflow.

Audit hiện tại:

- approveQuestion chưa kiểm tra đầy đủ Bloom/rubric; vì vậy APPROVED một mình không đủ để đưa vào bài thi.
- Java BloomLevel có 6 mức REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE, CREATE.
- File migration Question cũ và fixture PostgreSQL vẫn giới hạn 4 mức đầu. Đợt này không nới constraint, không sửa lịch sử migration hoặc database live.
- Validator hỗ trợ cả enum hiện hành; test unit kiểm tra đủ 6 mức. PostgreSQL test giữ nguyên migration, dùng các mức schema đó cho phép.
- Nếu cần lưu EVALUATE/CREATE trên database còn constraint 4 mức, phải audit và đề xuất migration riêng để chủ dự án phê duyệt. Đây không phải blocker cho selection trên các câu hợp lệ đang lưu.

Pool được đọc bằng native scalar projection QuestionSelectionRow trong package dto.projection (chỉ dùng nội bộ, không phải response API), không hydrate Question/Rubric để quyết định trên cache cũ của caller. Các row join criteria được gom theo questionId; không nhầm số tiêu chí với số câu.

## 3. Chính sách chọn và chống lặp

N = ExamSession.maxMainQuestions. Luôn chọn đúng N ID distinct, hoặc trả lỗi; không giảm N, không thêm câu AI và không lặp một câu hai lần trong cùng bài.

Lịch sử chỉ gồm các liên kết của sinh viên khác trong cùng ExamSession; không dùng lịch sử phiên/môn khác để phạt ứng viên.

Xác định thứ tự sinh viên trước:

1. Nếu có dữ liệu thực tế, dùng startedAt; completedAt chỉ là fallback khi legacy thiếu startedAt.
2. Các bài có thời gian thực tế được ưu tiên xác định previous hơn bộ chỉ chuẩn bị.
3. Khi chưa có bài thực sự bắt đầu, dùng max StudentExamQuestion.id của mỗi bộ làm fallback thứ tự persisted selection. Không tuyên bố đây là thời điểm thi chính xác.
4. Dùng ID liên kết lớn nhất phá hòa. Không suy ra sinh viên trước bằng questionOrder hoặc chỉ bằng slotOrder.

Xếp hạng ứng viên theo thứ tự ưu tiên, không cộng điểm trọng số:

1. Không thuộc bộ của previous student được ưu tiên trước.
2. Chưa dùng trong phiên được ưu tiên.
3. Đã dùng thì câu có lần dùng gần nhất xa hơn được ưu tiên.
4. Cùng độ gần thì câu có tổng số lần xuất hiện ít hơn được ưu tiên.
5. Ngang các tiêu chí trên thì Bloom ít có trong bộ đang chọn được ưu tiên.
6. Vẫn ngang nhau thì chọn random; production dùng SecureRandom, unit/PG fixture inject Random seed cố định.

Hệ quả:

- Nếu có ít nhất N câu ngoài bộ previous thì không lấy câu previous.
- Khi không thể tránh hết overlap, số câu previous được lấy là tối thiểu theo pool đang đủ điều kiện; vẫn đúng N distinct.
- Giảm lặp là best effort trên lịch sử persisted allocation, không phải bảo đảm tất cả sinh viên có bộ khác nhau.
- Bloom chỉ phá hòa, không hy sinh chính sách chống lặp để cưỡng bức tỷ lệ Bloom. Không cam kết exact balance.
- Câu mới vào bank sau lần đọc pool không được xét cho lượt đang chạy; những câu thực sự chọn được khóa và kiểm tra lại trước insert.

Thiếu pool:

- Có ít hơn N câu đạt điều kiện cơ bản: 409/1500.
- Có ít nhất N câu cơ bản nhưng không còn đủ N sau kiểm tra rubric: 409/1501.
- Rubric lỗi bị loại, không khiến cả ngân hàng thất bại nếu vẫn còn N câu hợp lệ khác.
- Không silently bốc bù nếu selected candidate đổi dữ liệu trước khi lấy khóa; trả lỗi để workflow tải lại rồi retry có chủ đích.

Giới hạn MVP: đọc toàn bộ pool/criteria và lịch sử trong phiên, thuật toán greedy ưu tiên rõ ràng, chưa tối ưu cho ngân hàng hàng trăm nghìn câu hoặc nhiều phiên đồng thời quy mô lớn.

## 4. Lưu trữ, thứ tự và retry

- Reuse entity StudentExamQuestion, bảng aives.student_exam_questions và FK hiện có.
- Insert đúng N liên kết, questionOrder=1..N theo thứ tự selector chọn; flush trong transaction caller.
- UNIQUE(student_exam_id,question_id) và UNIQUE(student_exam_id,question_order) là safeguard cuối.
- Caller rollback thì mọi link mới rollback; identity có thể có khoảng trống sau rollback, không xem ID liên tục là invariant.
- Bộ có sẵn đủ N, distinct, order liên tục từ 1 được trả nguyên ID và order. Không đọc bank/random lại.
- Bộ thiếu/thừa, order có lỗ hoặc N cấu hình đã đổi: 409/1502, không xóa, thêm hoặc thay thế bộ cũ.
- Retry của bộ hoàn chỉnh sau khi bài bắt đầu/hoàn tất vẫn chỉ đọc lại liên kết, không reset evidence hoặc trả quyền Start mới.
- Không dùng eligibility hiện tại (ví dụ archive sau thi) để tráo câu lịch sử. Việc trả lại bộ cũ không phải xác nhận trạng thái bài hoặc rubric đủ điều kiện bắt đầu lại.
- Parent maxMainQuestions hiện là metadata còn sửa được khi chỉ có bài ASSIGNED. Không thay CRUD này; nếu ai sửa N sau một bộ đã persisted thì consistency guard chặn. Vì vậy production phải selection và Start trong cùng transaction, không preselect hàng loạt rồi commit trước.

Tạo bộ mới chỉ khi StudentExam còn ASSIGNED và chưa có startedAt/completedAt, điểm, feedback hoặc audio; parent được UPCOMING hoặc ONGOING, không COMPLETED. Cho phép ONGOING để chuẩn bị sinh viên tiếp theo trong cùng phiên. Selector không tự kiểm tra slot/time window thay future Start.

## 5. Transaction và khóa

Giao thức cho selection:

1. Đọc scalar parent ID từ StudentExam; bài không tồn tại trả 404/1400.
2. ExamAccessPolicy.lockForInternalExamOperation khóa lecturer owner FOR UPDATE NOWAIT → subject FOR SHARE → parent ExamSession FOR UPDATE NOWAIT; refresh và kiểm tra owner/subject không đổi.
3. Khóa StudentExam FOR UPDATE NOWAIT (reuse query child mạnh hiện có), refresh và xác minh parent ID.
4. Khóa các link hiện có theo ID FOR UPDATE NOWAIT rồi kiểm tra bộ đã lưu.
5. Nếu chưa có bộ, đọc pool/history, chạy selector.
6. Khóa selected Question theo ID tăng dần bằng FOR SHARE NOWAIT.
7. Khóa rubric theo ID FOR UPDATE NOWAIT; sau đó criteria theo ID FOR SHARE NOWAIT.
8. Đọc lại scalar graph và kiểm tra điều kiện/fingerprint gồm version, Bloom, content, rubric ID, ID/text/weight/flag/order của criteria.
9. Insert/flush liên kết; caller tiếp tục kiểm tra và cập nhật Start, rồi mới commit.

Tại sao cần từng khóa:

- Cùng owner/parent khóa như CRUD/assignment để hai lượt không đọc history rỗng cùng lúc rồi lấy bộ trùng không cần thiết.
- Child FOR UPDATE xung đột FK KEY SHARE khi thêm câu, phối hợp với thu hồi để không mất evidence.
- Question FOR SHARE chặn cả non-key UPDATE status/content và DELETE; chỉ KEY SHARE không đủ.
- Rubric FOR UPDATE chặn FK KEY SHARE của INSERT criterion mới, bảo vệ membership của tập criteria.
- Criteria FOR SHARE chặn UPDATE/DELETE text/weight hiện có; khóa rubric một mình không chặn mọi non-key UPDATE trên child.
- Đọc lại scalar tránh persistence context trả bản cũ sau khi lock. Không clear EM của caller.
- Native SQL ngoài protocol vẫn có thể can thiệp sau commit; locks không thay snapshot lịch sử.

Các câu/rubric khóa theo ID ổn định. NOWAIT trả conflict thay chờ vòng; subject read lock dùng cơ chế hiện có và chịu timeout transaction caller. Hai phiên khác nhau của cùng lecturer vẫn bị serialize theo khóa user thô. Hai lecturer khác nhau có thể nhận conflict ngắn khi cùng chọn một rubric, do rubric lock mạnh.

Không bắt/nuốt lỗi rồi commit phần còn lại. AppException runtime làm transaction rollback-only; nếu caller giữ transaction lâu hoặc gọi external AI trong transaction sẽ tăng tranh chấp, không làm như vậy.

## 6. Hợp đồng tích hợp Future Start Student Exam

Chưa triển khai Start endpoint/service trong đợt này. Workflow tương lai cần:

1. Xác thực JWT và role hiện hành; student chỉ start bài của chính mình. ADMIN không được quyền ghi ngầm.
2. Mở một transaction ghi ngắn, timeout ví dụ 10 giây.
3. Khóa owner lecturer → subject → parent theo policy dùng chung. Nếu cần khóa user student để kiểm tra role, thực hiện sau parent và trước child, theo ID tăng dần.
4. Kiểm tra lại quyền sở hữu, phân công/chính sách sau thu hồi, lịch được phép thi, parent state và child state dưới khóa.
5. Gọi selectForStart(studentExamId) trước khi đặt IN_PROGRESS/startedAt hoặc sửa entity đang managed. Selector có refresh, caller không được gửi entity có thay đổi chưa flush để nó ghi đè.
6. Chỉ sau khi có bộ hợp lệ mới đặt trạng thái/timestamp bắt đầu theo workflow đã duyệt. Không dùng kết quả retry của bài COMPLETED làm lý do cho start lại.
7. Commit selection cùng state transition. Bất kỳ bước nào lỗi phải rollback toàn bộ.
8. Sau commit mới delivery/STT/TTS/AI theo hợp đồng riêng, không để lời gọi AI trong transaction giữ khóa.

Caller có thể giữ sẵn các khóa theo đúng thứ tự; cùng transaction lấy lại khóa parent/child không tạo transaction mới. Không dùng REQUIRES_NEW để tách selection khỏi Start.

Không có Swagger/Postman request selection ở pha này. Kiểm tra foundation qua unit/PG tests; không thêm test-only controller production.

## 7. Historical integrity và quyết định không migration

Audit source hiện tại:

- QuestionServiceImpl chỉ PATCH/DELETE DRAFT chưa archive; ADMIN cũng không bỏ qua trạng thái.
- requireUnused gọi countExamReferences để chặn sửa/xóa bản nháp đã tham chiếu, nếu có workflow tương lai chuyển nó về DRAFT.
- APPROVED không có đường sửa content/Bloom/referenceAnswer bằng CRUD hiện tại.
- Rubric được tạo cùng câu AI; chưa có API độc lập cập nhật/xóa rubric/criteria.
- FK từ StudentExamQuestion tới Question là ON DELETE RESTRICT; test PostgreSQL kiểm tra xóa câu đã chọn bị chặn.
- Thu hồi StudentExam đã có bất kỳ Question bị chặn bởi service phân công hiện có.

Với những đường ghi hiện hành và việc không sửa trực tiếp DB, liên kết FK + immutable approved/referenced content là đủ cho MVP. Không thêm snapshot/version column hoặc sửa quan hệ rubric.

Giới hạn quan trọng:

- FK tới Question không ngăn SQL trực tiếp sửa nội dung hoặc xóa/sửa rubric.
- @Version hiện có chỉ được kiểm tra trong lúc chọn, không phải version câu/rubric được lưu theo từng bài thi.
- Không thể chứng minh lịch sử bất biến nếu cho phép người quản trị hoặc writer khác sửa raw SQL sau khi thi.
- Trước khi mở Rubric CRUD, unapprove/edit referenced questions hoặc cho phép nhiều phiên bản nội dung, phải thiết kế writer giữ khóa Question và chặn mutation khi APPROVED/referenced, hoặc xin phê duyệt snapshot.
- Nếu cần snapshot, phương án nhỏ tương thích là thêm các cột snapshot nội dung/đáp án/Bloom/rubric JSON và version vào từng StudentExamQuestion, nullable cho legacy. Đây chỉ là đề xuất cần review; không tạo SQL hoặc thay DB trong đợt này.
- Snapshot rubric chỉ hữu ích khi cả nội dung, trọng số, tiêu chí và quy tắc grading được giữ cùng phiên bản; không snapshot một ID rubric rồi coi là bảo toàn.

## 8. Lỗi và dữ liệu nhạy cảm

| HTTP / code | Trường hợp |
| --- | --- |
| 404 / 1400 | StudentExam không tồn tại |
| 404 / 1300 | Parent mất trong quá trình đọc/khóa |
| 409 / 1500 | Không đủ câu distinct hợp lệ cơ bản |
| 409 / 1501 | Không đủ rubric dùng được hoặc selected rubric vừa mất hiệu lực |
| 409 / 1502 | Bộ đã lưu không đầy đủ/không nhất quán |
| 409 / 1503 | Selected question hoặc fingerprint rubric thay đổi trước khi khóa xong |
| 409 / 1504 | Không được tạo bộ mới với trạng thái hiện tại |
| 409 / 1505 | Khóa, timeout hoặc ràng buộc đồng thời khi selection |
| 500 / 9999 | Lỗi persistence/SQL ngoài dự kiến, thông tin chi tiết chỉ ghi ở server |

Lỗi chỉ dùng ErrorCode/AppException, không đưa câu, reference answer, SQL, rubric text hoặc điểm vào message. Không gộp/xóa exception handlers trong task này; không đổi error contract Feature 1. Caller tương lai ánh xạ AppException theo HTTP status/ErrorCode hiện có. Lỗi transaction MANDATORY/read-only là lỗi tích hợp caller, không phải API cho student retry tùy ý.

## 9. Kiểm thử

- ExamQuestionEligibilityTest: enum, metadata và rubric; thang điểm, decimals, text/order/null/duplicate, không tự sửa dữ liệu.
- ExamQuestionSelectorTest: đủ N distinct, history, previous student, fallback, Bloom và randomness có seed.
- ExamQuestionSelectionServiceTest: orchestration, retry, bộ không đầy đủ, thiếu pool/rubric và eligibility drift.
- ExamQuestionSelectionPostgresIT: native projection/repository, service/transaction/constraints, subject/status/archive/rubric, order, retry/evidence và rollback cùng Start giả lập.
- ExamQuestionSelectionConcurrencyPostgresIT: hai selection, hai sinh viên, thu hồi, eligibility thay đổi, ổn định cả graph rubric và rollback sau một INSERT thật.
- ExamQuestionSelectionPostgresConfig/Fixture/TestData: toàn bộ dữ liệu giả trong PostgreSQL container sở hữu, dùng migration thật và Hibernate validate. Không nạp .env/application.yaml, Supabase hoặc dịch vụ AI.
- Spy trong concurrency chỉ đặt latch hoặc inject failure sau SQL thật; core service/locks/repository chạy trên PostgreSQL thật. Không tuyên bố đã triển khai Start/AI vì test mô phỏng transaction Start.

Lệnh JDK 21:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify -Ppostgres-it -B -ntp
```

**BUILD SUCCESS** lần cuối lúc **15:01:41 +07:00, 19/10/2026**, tổng thời gian **2 phút**. JDK **21.0.10**, javac release 21, PostgreSQL **17.11** trong Docker/Testcontainers; biên dịch main/test và đóng gói thành công.

| Nhóm | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| Surefire | 317 | 0 | 0 | 0 |
| Failsafe PostgreSQL | 191 | 0 | 0 | 0 |
| Tổng | **508** | **0** | **0** | **0** |

**97 test mới**: eligibility 34, selector 12, service 11, repository/service/PostgreSQL 27 và concurrency PostgreSQL 13. **411 test hồi quy** trước đó tiếp tục đạt. Lượt full trước khi thêm hai ca lỗi SQL/parent cuối đạt 506 test; lượt cuối 508 là bằng chứng cho source bàn giao.

Report XML nằm tại target/surefire-reports và target/failsafe-reports. Log full build ở thư mục TEMP máy chạy, tên aives-exam-session-full-20261019.log. Không commit output build.

Không chạy profile external-services, không khởi động backend live hoặc kiểm tra grants/RLS Supabase, không gọi AI hoặc test UI. Các lỗi khóa 55P03 và lỗi fault injection xuất hiện trong log là dữ liệu kiểm thử có chủ đích; không phải test failure. Không còn blocker biên dịch hoặc Docker. Không tuyên bố đã test Start REST vì chưa có endpoint đó.

## 10. Git và phần còn lại

Không sửa entity, bảng, migration, dependencies, frontend hoặc secrets. Không commit/push/merge. Chỉ bổ sung service/DTO/support/projection/query và dùng lại lock policy hiện có; không thay CRUD Question, Exam Session hay assignment.

Git cuối: **15 file mới, 6 file sửa**, tổng 21 đường dẫn, không xóa/rename. `git diff --check` đạt. Đường dẫn tương đối từ backend:

```text
M docs/business_rules/feature2-student-assignment-scheduling.md
M src/main/java/com/avies/backend/exception/ErrorCode.java
M src/main/java/com/avies/backend/repository/QuestionRepository.java
M src/main/java/com/avies/backend/repository/RubricRepository.java
M src/main/java/com/avies/backend/repository/StudentExamQuestionRepository.java
M src/main/java/com/avies/backend/service/support/ExamAccessPolicy.java
?? docs/business_rules/feature2-question-selection.md
?? src/main/java/com/avies/backend/dto/response/SelectedMainQuestion.java
?? src/main/java/com/avies/backend/dto/projection/QuestionSelectionRow.java
?? src/main/java/com/avies/backend/service/ExamQuestionSelectionService.java
?? src/main/java/com/avies/backend/service/impl/ExamQuestionSelectionServiceImpl.java
?? src/main/java/com/avies/backend/service/support/ExamQuestionEligibility.java
?? src/main/java/com/avies/backend/service/support/ExamQuestionSelector.java
?? src/test/java/com/avies/backend/exam/ExamQuestionEligibilityTest.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectionConcurrencyPostgresIT.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectionFixture.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectionPostgresConfig.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectionPostgresIT.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectionServiceTest.java
?? src/test/java/com/avies/backend/exam/ExamQuestionSelectorTest.java
?? src/test/java/com/avies/backend/exam/ExamQuestionTestData.java
```

`git diff --stat` không tính file untracked; review cả `git status --short -uall` và `git ls-files --others --exclude-standard`. Chưa stage để thống kê hoặc tự tạo commit.

Phần còn lại:

1. Review foundation và hợp đồng Start trước khi nối vào workflow bắt đầu thi.
2. Implement Start authorization/time/state transition trong cùng transaction, không cho student tự regenerate.
3. Question delivery an toàn và AI viva/STT/TTS/WebSocket/follow-up/grading theo task riêng.
4. Review Rubric write paths/snapshot nếu mở chỉnh sửa lịch sử; xử lý Bloom schema 4/6 mức bằng migration riêng nếu thật sự cần.
5. Frontend/E2E và grants/RLS live chỉ kiểm tra trên môi trường được phép.
6. Tối ưu pool/history và concurrency cross-lecturer khi quy mô thực tế cần, không hứa exact Bloom hoặc không lặp tuyệt đối.

**QUESTION SELECTION COMPLETE — READY FOR REVIEW**

Dừng sau implementation và validation để chờ review; chưa triển khai lên Supabase.
