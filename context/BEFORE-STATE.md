# CoreDisc-BE 현재 상태 분석 (Before)

> 최적화 작업 전 현재 코드베이스의 정확한 상태를 기록합니다.
> 최적화 후 Before/After 비교의 기준점이 되는 문서입니다.

---

## 1. 전체 현황 요약

### 잘 되어 있는 것

| 항목 | 상태 | 설명 |
|------|------|------|
| Lazy Loading | ✅ 양호 | 모든 엔티티의 @ManyToOne, @OneToOne에 `FetchType.LAZY` 적용 |
| FetchJoin (QueryDSL) | ✅ 양호 | 피드/게시글 상세 조회에서 member, profileImg fetchJoin 적용 |
| 피드 답변 배치 조회 | ✅ 양호 | 게시글 ID 목록으로 IN 절 답변 배치 조회 (N+1 부분 방지) |
| 커서 기반 페이지네이션 | ✅ 양호 | 피드, 댓글에 커서 기반 무한스크롤 구현 |
| 좋아요 2중 방어 | ✅ 양호 | 애플리케이션 검증 + DB 유니크 제약조건 |
| CQRS 패턴 | ✅ 양호 | Command/Query 서비스 분리 |
| 유니크 제약조건 | ✅ 부분 | Post/Answer, PostLike, Disc, Member에 유니크 존재 |
| 모니터링 의존성 | ✅ 존재 | Actuator + Micrometer Prometheus 의존성 추가됨 |
| 비동기 메일 | ✅ 양호 | mailExecutor ThreadPool로 이메일 비동기 전송 |

### 부족한 것

| 항목 | 상태 | 심각도 | 설명 |
|------|------|--------|------|
| DB 인덱스 | ❌ 없음 | 🔴 심각 | 커스텀 인덱스 0개 (PK/FK 자동 인덱스만 존재) |
| 캐싱 | ❌ 없음 | 🔴 심각 | Caffeine/Spring Cache 미사용, @Cacheable 0건 |
| S3 비동기 처리 | ❌ 없음 | 🔴 심각 | 원본/썸네일 동기 직렬 업로드 |
| FCM 비동기 처리 | ❌ 없음 | 🔴 심각 | 좋아요/댓글에서 FCM 동기 전송 |
| 읽기 전용 트랜잭션 | ❌ 누락 | 🟡 중간 | PostQueryService에 @Transactional(readOnly=true) 없음 |
| Redis 고급 연산 | ❌ 없음 | 🟡 중간 | increment/decrement/pipeline 미구현 |
| 배치 최적화 | ❌ 없음 | 🟡 중간 | 전체 로딩 + 건건이 S3 삭제 |
| 커넥션 풀 튜닝 | ❓ 불명 | 🟡 중간 | application.yml 미확인 (Secrets 관리) |
| 부하 테스트 | ❌ 없음 | 🟡 중간 | 성능 측정 도구/스크립트 없음 |

---

## 2. 엔티티별 인덱스 현황

### 현재 상태: 커스텀 인덱스 0개

JPA가 자동 생성하는 PK(id), FK(외래키), 유니크 제약조건 인덱스만 존재합니다.

| 엔티티 | @Table indexes | 유니크 제약 | FK 자동 인덱스 | 필요한 인덱스 |
|--------|:---:|:---:|:---:|------|
| **Post** | ❌ | ❌ | member_id | `(member_id, status)`, `(status, created_at)`, `(member_id, status, created_at)` |
| **PostAnswer** | ❌ | ✅ (post_id, answer_order) | post_id | 유니크가 커버 |
| **PostAnswerImage** | ❌ | ❌ | post_answer_id | 불필요 (1:1) |
| **PostLike** | ❌ | ✅ (post_id, member_id) | post_id, member_id | 유니크가 커버 |
| **Comment** | ❌ | ❌ | post_id, member_id, parent_id | `(post_id, depth, id)`, `(parent_id, id)` |
| **Follow** | ❌ | ❌ | follower_id, following_id | `(follower_id, is_circle)`, `(following_id)` |
| **Member** | ❌ | ✅ (email), ✅ (username), ✅ (nickname) | - | 유니크가 커버 |
| **Notification** | ❌ | ❌ | receiver_id, sender_id | `(receiver_id, created_at)` |
| **TodayQuestion** | ❌ | ❌ | member_id 등 | `(member_id, question_order, selected_date)` |
| **Device** | ❌ | ❌ | member_id | `(member_id, is_active)` |
| **Disc** | ❌ | ✅ (year, month, member_id) | member_id | 유니크가 커버 |
| **Block** | ❌ | ❌ | blocker_id, blocked_id | `(blocker_id, blocked_id)` 유니크 필요 |
| **SearchHistory** | ❌ | ❌ | member_id | `(member_id, created_at)` |
| **NotificationRead** | ❌ | ❌ | notification_id, member_id | `(member_id, notification_id)` |
| **ProfileImg** | ❌ | ❌ | member_id | 불필요 (1:1) |

---

## 3. @Transactional 현황

### 서비스별 트랜잭션 설정

| 서비스 | 클래스 레벨 | readOnly | 개별 메서드 | 상태 |
|--------|:---:|:---:|:---:|:---:|
| PostCommandServiceImpl | ❌ | - | ✅ 각 메서드에 @Transactional | 🟡 |
| **PostQueryServiceImpl** | **❌** | **❌** | **❌ 없음** | **🔴 누락** |
| CommentCommandServiceImpl | ✅ @Transactional | ❌ | 클래스에서 상속 | ✅ |
| CommentQueryServiceImpl | ✅ @Transactional | ✅ readOnly=true | 클래스에서 상속 | ✅ |
| PostLikeCommandServiceImpl | ❌ | - | ✅ 각 메서드에 @Transactional | 🟡 |
| NotificationCommandServiceImpl | ✅ @Transactional | ❌ | 클래스에서 상속 | ✅ |
| **NotificationQueryServiceImpl** | **❌** | **❌** | **❌ 없음** | **🔴 누락** |
| AuthCommandServiceImpl | ✅ @Transactional | ❌ | 클래스에서 상속 | ✅ |
| MemberQueryServiceImpl | 확인 필요 | - | - | ❓ |

**문제**: `PostQueryServiceImpl`과 `NotificationQueryServiceImpl`에 `@Transactional(readOnly = true)`가 없어서:
- JPA 더티 체킹 비용이 불필요하게 발생
- DB 리플리카 라우팅 불가 (읽기 전용 쿼리를 Master로 전송)

---

## 4. 비동기 처리 현황

### @Async 사용 현황

| 영역 | @Async | 현재 동작 | 문제 |
|------|:---:|------|------|
| 이메일 발송 | ✅ | mailExecutor 스레드풀 사용 | 정상 |
| **S3 이미지 업로드** | **❌** | **동기 직렬 (원본→썸네일 순차)** | **API 응답 1~2초 지연** |
| **S3 이미지 삭제** | **❌** | **동기 루프 (건건이 삭제)** | **삭제 수 × 200ms** |
| **FCM 푸시 (좋아요)** | **❌** | **동기 루프 (디바이스당 500ms+)** | **좋아요 응답 2초+** |
| **FCM 푸시 (댓글)** | **❌** | **동기 루프 (동일 패턴)** | **댓글 응답 2초+** |
| **FCM 푸시 (대댓글)** | **❌** | **동기 루프 (동일 패턴)** | **대댓글 응답 2초+** |
| 배치 스케줄러 | ❌ | 메인 스케줄러 스레드에서 동기 실행 | 배치 중 다른 스케줄 지연 |

### AsyncConfig 현재 설정

```
등록된 Executor: mailExecutor 1개만 존재
  - corePoolSize: 2
  - maxPoolSize: 5
  - queueCapacity: 10
  - threadNamePrefix: "Async MailExecutor-"

필요한 Executor:
  ❌ imageExecutor (S3 업로드용)
  ❌ notificationExecutor (FCM 전송용)
  ❌ batchExecutor (배치 작업용)
```

---

## 5. 캐싱 현황

### 현재 상태: 캐싱 레이어 전무

```
@EnableCaching:     ❌ 없음
@Cacheable 사용:    0건
@CacheEvict 사용:   0건
Caffeine 의존성:    ❌ 없음 (build.gradle에 미포함)
Spring Cache 설정:  ❌ 없음
```

### Redis 사용 현황 (캐싱이 아닌 저장소 용도)

| 용도 | 키 패턴 | TTL | 설명 |
|------|--------|-----|------|
| JWT Refresh Token | `{username}` | 리프레시 만료시간 | 토큰 재발급용 |
| 로그아웃 블랙리스트 | `{accessToken}` | 액세스 남은 수명 | 로그아웃된 토큰 차단 |
| 이메일 인증 코드 | `auth:{email}:{type}` | 10분 | 회원가입/비밀번호 재설정 |

### RedisUtil 지원 연산

```
✅ set(key, value)
✅ get(key)
✅ exists(key)
✅ expire(key, timeout, unit)
✅ delete(key)

❌ increment() / decrement()    — 좋아요 카운터에 필요
❌ mget() / mset()              — 배치 조회에 필요
❌ hset() / hget() / hgetAll()  — 복합 데이터 캐싱에 필요
❌ setIfAbsent() (setnx)        — 분산 락에 필요
❌ pipeline()                    — 대량 연산에 필요
```

---

## 6. S3 이미지 처리 현황

### AmazonS3Manager 현재 구현

```
업로드 흐름 (동기 직렬):
┌─────────────────────────────────────────────┐
│ 1. validateFile()           ~5ms            │
│ 2. generateKey()            ~1ms            │
│ 3. uploadToS3(원본)         ~500-1000ms  ⏱  │ ← 동기 블로킹
│ 4. createThumbnail()        ~300-800ms   ⏱  │ ← CPU 집중 (EXIF+리사이즈)
│ 5. uploadToS3(썸네일)       ~300-500ms   ⏱  │ ← 동기 블로킹
└─────────────────────────────────────────────┘
총: 1,100ms ~ 2,300ms (직렬 합산)

삭제 흐름 (동기 루프):
for each imageUrl:
    amazonS3.deleteObject(bucket, key);   // ~200ms/건 × N건
```

### EXIF 처리: ✅ 구현됨

```
- metadata-extractor 2.18.0 사용
- Orientation 1, 3, 6, 8 처리
- AffineTransform으로 회전
- BILINEAR 보간법 + ANTIALIAS 적용
- 썸네일 800×800 이내 리사이즈
```

### 문제점

1. **원본 업로드 ↔ 썸네일 생성/업로드가 직렬** → 병렬화 가능
2. **@Transactional 내부에서 S3 I/O** → DB 커넥션 장시간 점유
3. **InputStream 이중 소비 가능성** → byte[] 1회 읽기로 개선 필요
4. **S3 건건이 삭제** → DeleteObjects API 미사용 (배치 삭제 가능)

---

## 7. 피드 쿼리 현황

### QueryPostRepositoryImpl — findPostFeed()

```
현재 쿼리 실행 흐름 (게시글 10개 피드 기준):

쿼리 1: 게시글 + 작성자 + 프로필 (FetchJoin)          ✅ 양호
  SELECT post LEFT JOIN member LEFT JOIN profileImg
  WHERE status=PUBLISHED AND member_id IN (팔로잉) AND visibility
  ORDER BY id DESC LIMIT 11

  ⚠️ 문제: 팔로잉 ID 조회가 서브쿼리로 3번 중복 실행

쿼리 2: 답변 + 이미지 배치 조회 (IN 절)                ✅ 양호
  SELECT postAnswer LEFT JOIN postAnswerImage
  WHERE post_id IN (조회된 게시글 IDs)
  ORDER BY post_id ASC, answerOrder ASC

쿼리 3~12: 질문 내용 개별 조회 (N+1 문제)              ❌ 문제
  각 게시글마다:
    SELECT todayQuestion WHERE member=? AND order=? AND date BETWEEN ?
  → 게시글 10개 = 10개 추가 쿼리

총 쿼리 수: 2 + 10 = 12개 (최소)
         : 2 + 40 = 42개 (상세 조회 시)
```

### 팔로우 서브쿼리 중복

```
피드 쿼리 내부에서 동일한 follow 테이블을 3번 서브쿼리:
  1. 피드 대상 회원 필터링:     SELECT following_id FROM follow WHERE follower_id=?
  2. CORE 서클 필터링:         SELECT following_id FROM follow WHERE follower_id=? AND is_circle=true
  3. CIRCLE 게시글 가시성:     SELECT 1 FROM follow WHERE follower_id=post.member AND following_id=?
```

---

## 8. 댓글 쿼리 현황

### commentQueryRepositoryImpl

```
부모 댓글 조회:
  SELECT comment WHERE post_id=? AND depth=0 ORDER BY id DESC LIMIT size+1
  → fetchJoin 없음 → comment.member 접근 시 N+1 가능

대댓글 조회:
  SELECT comment WHERE parent_id=? ORDER BY id DESC LIMIT size+1
  → 동일 패턴
```

### 서비스 레이어 N+1

```java
// CommentQueryServiceImpl — 부모 댓글 목록 조회
comments.stream().map(comment -> {
    comment.hasChild()      // ← replies 컬렉션 LAZY 초기화 → SELECT 쿼리!
    comment.getReplyCount() // ← 이미 로드된 컬렉션 재사용 (추가 쿼리 없음)
    comment.isOwner(...)    // ← 단순 비교 (쿼리 없음)
});

// 부모 댓글 10개 조회 시:
// 쿼리 1: 부모 댓글 조회
// 쿼리 2~11: 각 댓글의 replies 컬렉션 로딩 (hasChild 호출)
// 총: 1 + 10 = 11개 쿼리
```

---

## 9. 좋아요 현황

### PostLikeCommandServiceImpl

```
현재 좋아요 흐름:
1. existsByMemberAndPost()       ~5ms     (애플리케이션 검증)
2. PostLike.create() + save()    ~30ms    (DB 저장)
3. createNotification()          ~20ms    (알림 DB 저장)
4. findByMemberAndIsActiveTrue() ~10ms    (디바이스 목록 조회)
5. for each device:                       (FCM 동기 루프)
     isTokenValid()              ~200ms
     sendNotificationToToken()   ~500ms
─────────────────────────────────
디바이스 1개: ~765ms
디바이스 3개: ~2,165ms  ← 좋아요 하나에 2초
```

### likeCount 비정규화 미사용

```
Post 엔티티:
  private int likeCount = 0;    // 필드 존재
  private int commentCount = 0;  // 필드 존재
  private int viewCount = 0;     // 필드 존재

좋아요/댓글 생성/삭제 시:
  ❌ likeCount 증감 코드 없음
  ❌ commentCount 증감 코드 없음
  ❌ viewCount 증감 코드 없음
  → 항상 0으로 남아있음
  → 피드에서 카운트를 보여주려면 매번 COUNT 쿼리 필요
```

---

## 10. 배치 처리 현황

### BatchScheduler

```
매일 자정 실행 (cron: 0 0 0 * * *):
  1. cleanupOldTempPosts()                    — 임시 게시글 정리
  2. generateDailyStatistics()                — 일간 통계
  3. generateMonthlyFixedQuestionStats()      — 월간 고정 질문 통계
  4. generateRandomQuestionsStats()           — 랜덤 질문 통계
  5. generateMonthlySelectionDiaryStats()     — 선택 일기 통계

문제:
  - 5개 작업이 순차 실행 (병렬 가능한 것도 직렬)
  - cleanupOldTempPosts: 전체 로딩 + 건건이 S3 삭제 + 하나의 거대 트랜잭션
  - 1000개 임시 게시글 정리 시: S3 2000건 × 200ms = ~400초
```

### NotificationScheduler

```
매일 23시 실행 (cron: 0 0 23 * * *):
  - 임시 저장 알림 발송
  - FCM 동기 전송 (디바이스 루프)

문제:
  - 사용자 수만큼 FCM 동기 호출
  - 1000명 × 3 디바이스 = 3000번 FCM 호출 = ~25분
```

---

## 11. 설정 현황

### build.gradle 의존성

```
✅ 있음:
  - spring-boot-starter-web
  - spring-boot-starter-data-jpa
  - spring-boot-starter-security
  - spring-boot-starter-data-redis
  - spring-boot-starter-actuator
  - spring-boot-starter-mail
  - spring-boot-starter-validation
  - querydsl-jpa:5.0.0
  - jjwt:0.12.3
  - firebase-admin:9.5.0
  - metadata-extractor:2.18.0
  - springdoc-openapi:2.7.0
  - micrometer-registry-prometheus
  - mysql-connector

❌ 없음 (최적화에 필요):
  - spring-boot-starter-cache
  - caffeine (로컬 캐시)
  - p6spy (SQL 로깅/분석)
  - spring-boot-starter-aop (AOP 기반 로깅)
```

### application.yml

```
GitHub Secrets로 관리되어 로컬에 파일 없음.
확인이 필요한 설정:
  - spring.jpa.hibernate.ddl-auto: ?
  - spring.jpa.properties.hibernate.default_batch_fetch_size: ?
  - spring.datasource.hikari.*: ?
  - spring.data.redis.lettuce.pool.*: ?
  - logging.level.org.hibernate.SQL: ?
```

---

## 12. 모니터링 현황

### 의존성: ✅ 존재

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

### 설정/활용: ❓ 불확실

```
SecurityConfig에서 /actuator/** 허용: ✅

확인 필요:
  - Prometheus가 실제로 스크래핑하고 있는지
  - Grafana 대시보드가 구성되어 있는지
  - 커스텀 메트릭이 등록되어 있는지
  - 알림 룰이 설정되어 있는지
```

---

## 13. 예상 성능 기준선 (Before 수치)

> 부하 테스트 전 코드 분석 기반 예상치입니다.
> 실제 부하 테스트 후 정확한 수치로 업데이트해야 합니다.

| API | 예상 p95 응답시간 | 쿼리 수 | 병목 원인 |
|-----|-----------------|--------|----------|
| 피드 조회 (10건) | ~300ms | 12~42개 | N+1 (질문) + 서브쿼리 중복 |
| 게시글 상세 | ~100ms | 6~8개 | 질문 4개 개별 조회 |
| 이미지 업로드 | ~2,300ms | 2~3개 | 동기 S3 직렬 업로드 |
| 좋아요 | ~2,150ms | 4~6개 | 동기 FCM 루프 |
| 댓글 작성 | ~2,000ms | 3~5개 | 동기 FCM 루프 |
| 부모 댓글 조회 (10건) | ~150ms | 11개 | N+1 (hasChild) |
| 배치 정리 (1000건) | ~400초 | 1000+ | 동기 S3 건건 삭제 |

---

## 14. 현재 잘 한 것 (포트폴리오에 이미 쓸 수 있는 것)

최적화 이전에도 아래 항목은 이미 좋은 설계 결정입니다:

| 항목 | 설명 | 포트폴리오 가치 |
|------|------|:---:|
| **Lazy Loading 전면 적용** | 모든 엔티티에 LAZY fetch 일관 적용 | ★★★ |
| **FetchJoin 선별 사용** | 피드/상세 조회에서 필요한 관계만 fetchJoin | ★★★ |
| **커서 기반 페이지네이션** | 오프셋 대신 커서 방식으로 무한스크롤 | ★★★★ |
| **좋아요 2중 방어** | App 검증 + DB 유니크로 Race Condition 방지 | ★★★★ |
| **CQRS 패턴** | 읽기/쓰기 서비스 분리 | ★★★ |
| **EXIF 방향 보정** | 모바일 사진 회전 문제 서버 측 해결 | ★★★★ |
| **S3 삭제 복원력** | try-catch로 S3 장애가 DB 삭제를 차단하지 않음 | ★★★ |
| **비동기 메일** | mailExecutor로 이메일 비동기 전송 | ★★★ |
| **답변 배치 페칭** | 피드에서 답변을 IN 절로 배치 조회 | ★★★★ |
| **Repository 3계층 추상화** | Domain Interface → Adaptor → JPA+QueryDSL | ★★★ |
