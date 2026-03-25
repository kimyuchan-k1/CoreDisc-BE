# 도메인 엔티티 & 관계

## ERD 개요

```
┌──────────┐     ┌──────────┐     ┌──────────────┐
│  Member   │────<│   Post    │────<│  PostAnswer   │
│           │     │           │     │  (1~4개)      │
│           │     └──────────┘     └──────┬───────┘
│           │          │                   │
│           │          │              ┌────┴─────────┐
│           │     ┌────┴────┐        │PostAnswerImage│
│           │────<│ Comment  │        └──────────────┘
│           │     │(계층형)  │
│           │     └─────────┘
│           │
│           │────<│ PostLike │
│           │────<│ Follow   │────> Member
│           │────<│ Block    │────> Member
│           │────<│ Device   │
│           │────<│ Disc     │
│           │────<│ ProfileImg│
│           │────<│ SearchHistory│
│           │────<│ Notification │
│           │────<│ MemberOfficialQuestion│
│           │────<│ NotificationReminderSetting│
└──────────┘
```

## 핵심 엔티티

### Member (회원)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| email | String | 이메일 (unique) |
| username | String | 아이디 (unique) |
| nickname | String | 닉네임 (unique) |
| name | String | 이름 |
| password | String | 암호화된 비밀번호 |
| role | Role (Enum) | USER, ADMIN |
| oauthType | OauthType | KAKAO, NAVER |
| oauthId | String | OAuth 고유 ID |
| followerCount | int | 팔로워 수 |
| followingCount | int | 팔로잉 수 |
| createdAt / updatedAt | LocalDateTime | 감사 필드 (BaseEntity) |

### Post (게시글 = 하루의 저널)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| member | Member | 작성자 (FK) |
| publicity | PublicityType | OFFICIAL / CIRCLE / PERSONAL |
| status | PostStatus | TEMP / PUBLISHED |
| diaryWho | DiaryWho | 선택 일기 - 누구 |
| diaryWhere | DiaryWhere | 선택 일기 - 어디서 |
| diaryWhat | DiaryWhat | 선택 일기 - 무엇을 |
| likeCount | int | 좋아요 수 |
| commentCount | int | 댓글 수 |
| createdAt / updatedAt | LocalDateTime | 감사 필드 |

### PostAnswer (질문별 답변)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| post | Post | 소속 게시글 (FK) |
| answerOrder | int | 답변 순서 (1~4) |
| type | AnswerType | IMAGE / TEXT |
| textContent | String | 텍스트 답변 내용 |
| todayQuestion | TodayQuestion | 연결된 오늘의 질문 |

- **Unique Constraint**: (post_id, answer_order)

### PostAnswerImage (답변 이미지)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| postAnswer | PostAnswer | 소속 답변 (FK, 1:1) |
| originalUrl | String | S3 원본 이미지 URL |
| thumbnailUrl | String | S3 썸네일 이미지 URL |
| orientation | int | EXIF 회전 정보 |

### Comment (댓글 - 계층형)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| post | Post | 소속 게시글 (FK) |
| member | Member | 작성자 (FK) |
| content | String | 댓글 내용 |
| depth | int | 깊이 (0=댓글, 1=대댓글) |
| parent | Comment | 부모 댓글 (FK, self-referencing) |
| replies | List<Comment> | 자식 댓글 |
| isDeleted | boolean | 소프트 삭제 플래그 |

### Notification (알림)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| receiver | Member | 알림 수신자 (FK) |
| sender | Member | 알림 발신자 (FK) |
| type | NotificationType | 알림 유형 |
| targetId | Long | 이동 대상 ID |

**NotificationType 종류**:
- `DAILY_REMINDER` / `DAILY_REMINDER_ANSWER` : 일일 리마인더
- `UNANSWERED_QUESTION` / `UNANSWERED_QUESTION_ANSWER` : 미답변 질문
- `MONTHLY_REPORT` : 월간 리포트
- `FOLLOW` : 팔로우 알림
- `SHARED_SAVED` : 공유 질문 저장
- `COMMENT` / `COMMENT_REPLY` : 댓글/대댓글
- `LIKE` : 좋아요
- `TEMP_POSTS` : 임시 저장 알림

### Question (질문 - 이중 모델)

**OfficialQuestion (공식 질문)**
| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| content | String | 질문 내용 |
| scope | QuestionScope | PUBLIC / PRIVATE |
| questionType | QuestionType | FIXED / RANDOM |

**PersonalQuestion (개인 질문)**
| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| member | Member | 작성자 (FK) |
| content | String | 질문 내용 |

### TodayQuestion (오늘의 질문 선택)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| officialQuestion | OfficialQuestion | 공식 질문 (FK, nullable) |
| personalQuestion | PersonalQuestion | 개인 질문 (FK, nullable) |
| post | Post | 연결된 게시글 |

### Follow (팔로우 관계)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| follower | Member | 팔로우 하는 사람 (FK) |
| following | Member | 팔로우 받는 사람 (FK) |
| isCircle | boolean | 서클 여부 |

### Disc (월별 저널 컬렉션)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| member | Member | 소유자 (FK) |
| year | int | 연도 |
| month | int | 월 |
| coverColor | DiscCoverColor | 커버 색상 |
| coverImageUrl | String | 커버 이미지 URL |

- **Unique Constraint**: (year, month, member_id)

## Enum 목록 (18개)

| Enum | 값 |
|------|----|
| Role | USER, ADMIN |
| PublicityType | OFFICIAL, CIRCLE, PERSONAL |
| PostStatus | TEMP, PUBLISHED |
| AnswerType | IMAGE, TEXT |
| QuestionType | FIXED, RANDOM |
| QuestionScope | PUBLIC, PRIVATE |
| NotificationType | DAILY_REMINDER, COMMENT, REPLY, LIKE, FOLLOW 등 11종 |
| NotificationFrequency | Daily, Weekly, Monthly, Off |
| DiaryWho | 선택 일기 "누구" 옵션들 |
| DiaryWhere | 선택 일기 "어디서" 옵션들 |
| DiaryWhat | 선택 일기 "무엇을" 옵션들 |
| DiscCoverColor | 디스크 커버 색상 옵션들 |
| OauthType | KAKAO, NAVER |
| EmailRequestType | SIGNUP, RESET_PASSWORD |
| FeedType | FOLLOWING, CIRCLE, POPULAR 등 |
| SearchType | QUESTION, MEMBER |
| TermsType | SERVICE, PRIVACY, MARKETING 등 |
| TimeZoneType | 타임존 처리 |

## 공통 부모 엔티티

### BaseEntity (감사 필드)
```java
@MappedSuperclass
public abstract class BaseEntity {
    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```
