# API 엔드포인트 목록

총 18개의 컨트롤러가 존재합니다.

---

## 1. AuthController (`/api/auth`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/signup` | 회원가입 (이메일 인증 포함) |
| POST | `/login` | 로그인 |
| POST | `/reissue` | 토큰 재발급 |
| POST | `/logout` | 로그아웃 (디바이스 토큰 포함) |
| GET | `/check-username` | 아이디 중복 확인 |
| GET | `/check-email` | 이메일 중복 확인 |
| GET | `/check-nickname` | 닉네임 중복 확인 |
| POST | `/send-code` | 이메일 인증 코드 발송 |
| POST | `/signup/verify-code` | 회원가입 인증 코드 확인 |
| POST | `/reset-password/verify-code` | 비밀번호 재설정 인증 코드 확인 |
| POST | `/username` | 이메일로 아이디 찾기 |
| POST | `/password-reset/verify-user` | 비밀번호 재설정 시작 |
| POST | `/social/kakao` | 카카오 소셜 로그인 |
| POST | `/social/naver` | 네이버 소셜 로그인 |

---

## 2. PostController (`/api/posts`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/` | 빈 게시글(임시저장) 생성 |
| PUT | `/{postId}/answers/{questionOrder}/text` | 텍스트 답변 수정 |
| PUT | `/{postId}/answers/{questionOrder}/image` | 이미지 답변 수정 |
| GET | `/temp/{postId}` | 임시 저장 게시글 상세 조회 |
| GET | `/temp` | 특정 날짜의 임시 저장 게시글 목록 |
| POST | `/{postId}/likes` | 좋아요 |
| DELETE | `/{postId}/likes` | 좋아요 취소 |
| PUT | `/{postId}/publish` | 게시글 발행 (공개범위 설정) |
| GET | `/{postId}` | 게시글 상세 조회 |
| GET | `/` | 피드 조회 (커서 기반 페이지네이션, FeedType) |
| DELETE | `/{postId}` | 게시글 삭제 |

---

## 3. MemberController (`/api/members`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| PATCH | `/password` | 비밀번호 재설정 |
| PATCH | `/profile` | 프로필 수정 (닉네임/아이디) |
| PATCH | `/resign` | 회원 탈퇴 |
| GET | `/my-home` | 내 프로필 정보 조회 |
| GET | `/my-home/{targetUsername}` | 타 사용자 프로필 조회 |
| GET | `/my-home/posts` | 내 게시글 목록 |
| GET | `/my-home/posts/{targetUsername}` | 타 사용자 게시글 목록 |
| PATCH | `/my-home/email` | 이메일 변경 |
| PATCH | `/my-home/password` | 비밀번호 변경 |
| PATCH | `/my-home/username` | 아이디 변경 |
| PATCH | `/profile-image` | 프로필 이미지 업로드 |
| PATCH | `/profile-image/default` | 기본 프로필 이미지 설정 |
| GET | `/me/social` | 소셜 로그인 여부 확인 |
| GET | `/my-home/questions` | 카테고리별 내 질문 조회 |

---

## 4. CommentController (`/api/comments`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/posts/{postId}/comments` | 댓글 작성 |
| POST | `/comments/{commentId}/replies` | 대댓글 작성 |
| GET | `/posts/{postId}/comments` | 부모 댓글 목록 (커서 기반) |
| GET | `/comments/{commentId}/replies` | 대댓글 목록 (커서 기반) |
| DELETE | `/comments/{commentId}` | 댓글 삭제 |

---

## 5. FollowController (`/api/follow`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/{targetId}` | 팔로우 |
| DELETE | `/followings/{targetId}` | 언팔로우 |
| GET | `/followers` | 내 팔로워 목록 |
| GET | `/followings` | 내 팔로잉 목록 |
| GET | `/followers/{targetUsername}` | 타 사용자 팔로워 목록 |
| GET | `/followings/{targetUsername}` | 타 사용자 팔로잉 목록 |

---

## 6. NotificationController (`/api/notifications`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/unread` | 읽지 않은 알림 존재 여부 |
| PATCH | `/{notificationId}/read` | 알림 읽음 처리 |
| PATCH | `/read-all` | 전체 알림 읽음 처리 |
| GET | `/` | 알림 목록 (커서 기반 페이지네이션) |
| GET | `/notification-settings/reminder` | 리마인더 설정 조회 |
| PATCH | `/notification-settings/reminder` | 리마인더 설정 변경 |

---

## 7. QuestionController (`/api/questions`)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/personal` | 개인 질문 저장 |
| POST | `/official` | 질문 공유 (공식 질문으로 등록) |
| GET | `/basic` | 카테고리별 기본 질문 조회 |
| GET | `/basic/search` | 기본 질문 검색 |

---

## 8. BlockController (`/api/block`)

사용자 차단/차단해제/차단 목록 조회

## 9. CategoryController (`/api/categories`)

질문 카테고리 목록 조회

## 10. CalendarController (`/api/calendar`)

월별 캘린더 뷰 조회 (게시글 작성 날짜 표시)

## 11. DiscController (`/api/discs`)

월별 디스크 관리 (커버 색상/이미지 변경)

## 12. DeviceController (`/api/devices`)

FCM 디바이스 토큰 등록/삭제

## 13. SearchController (`/api/search`)

질문/회원 검색, 검색 이력 관리

## 14. CircleController (`/api/circle`)

서클(친한 친구) 관리 (추가/삭제)

## 15. FileController (`/api/files`)

파일 업로드 (S3)

## 16. TermsController (`/api/terms`)

약관 동의 관리

## 17. ReportStatController (`/api/report-stats`)

통계 리포트 조회 (일간/월간)

## 18. TestController

테스트용 엔드포인트

---

## 공통 응답 형식

```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "성공입니다.",
  "result": { ... }
}
```

## 페이지네이션

커서 기반 페이지네이션을 사용합니다:
- `cursor` : 마지막 항목의 ID
- `size` : 페이지 크기
- `hasNext` : 다음 페이지 존재 여부
