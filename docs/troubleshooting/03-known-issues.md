# 03. 알려진 이슈 (미수정)

부하 테스트 과정에서 발견했으나 아직 수정하지 않은 앱 레벨 이슈들입니다.

---

## 이슈 1: CommentController 경로 매핑 버그

### 증상
`GET /api/comments/{postId}` 호출 시 401 Unauthorized 반환.

### 원인
```java
// CommentController.java:17
@RestController("/api/")  // ❌ 이것은 빈 이름 설정, 경로 prefix가 아님
@RequestMapping("/api/")  // 이것이 경로 prefix 설정
public class CommentController {
```

`@RestController`의 `value`는 빈 이름이지 URL prefix가 아닙니다. 실제 경로는 `@RequestMapping`으로 설정해야 합니다.

### 영향
- 댓글 관련 모든 엔드포인트가 의도한 경로로 매핑되지 않을 수 있음
- 현재는 `@RequestMapping("/api/")`가 별도로 있어 동작하는 것으로 보이지만, 다른 Controller와의 일관성 확인 필요

### 상태
미수정 — k6 테스트에서 댓글 시나리오 제외 중

---

## 이슈 2: PostDetail의 질문 조회 시 데이터 부재 처리

### 증상
`GET /api/posts/{postId}` (포스트 상세 조회) 시 500 에러 발생.

### 원인
`PostQueryServiceImpl.findQuestionContent()`에서 해당 날짜/멤버의 TodayQuestion이 없을 때 발생.

수정 전에는 `.get()` 호출로 `NoSuchElementException`이 발생했으며, 현재는 `.ifPresent()`로 수정하여 누락된 질문은 건너뛰도록 변경.

다만 질문이 4개 미만으로 반환될 수 있어, 프론트엔드에서 이를 처리해야 할 수 있습니다.

### 상태
부분 수정 — `.get()` → `.ifPresent()` 변경 완료. 비즈니스 로직 측면에서 질문이 없는 경우의 UX 결정 필요.

---

## 이슈 3: TodayQuestion 데이터 정합성

### 문제
`today_question` 테이블에 `(member_id, question_order, selected_date)` 조합에 UNIQUE 제약조건이 없습니다.

### 영향
- 같은 멤버의 같은 질문 순서에 같은 날짜로 여러 레코드가 생성될 수 있음
- `findFirstBy`로 런타임 에러는 방지했으나, 데이터 정합성 문제는 잔존
- 어떤 레코드가 "정확한" 것인지 보장할 수 없음

### 권장 수정
```sql
ALTER TABLE today_question
ADD UNIQUE INDEX uk_member_order_date (member_id, question_order, selected_date);
```

주의: 기존 중복 데이터 정리 후 적용 필요.

### 상태
미수정 — 운영 데이터 영향도 분석 후 적용 권장
