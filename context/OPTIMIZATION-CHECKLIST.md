# CoreDisc-BE 최적화 작업 체크리스트

> 최적화 작업을 **측정 → 개선 → 검증** 사이클로 진행합니다.
> 각 Phase는 의존성 순서대로 나열되어 있으며, 이전 Phase 완료 후 다음으로 진행합니다.

---

## 작업 흐름 전체 로드맵

```
Phase 0: 측정 환경 구축 (부하 테스트 + 모니터링)
    ↓
Phase 1: 기초 체력 (인덱스 + 트랜잭션 + SQL 분석)
    ↓
Phase 2: 쿼리 최적화 (N+1 해결 + 피드 개선)
    ↓
Phase 3: 비동기 전환 (S3 + FCM + 이벤트 기반)
    ↓
Phase 4: 캐싱 레이어 (Redis + Caffeine)
    ↓
Phase 5: 배치 최적화 (청크 처리 + S3 Batch Delete)
    ↓
Phase 6: 최종 검증 (부하 테스트 Before/After)
```

---

---

## Phase 0: 측정 환경 구축

> **"측정하지 않으면 최적화할 수 없다."**
> 모든 최적화의 전제조건입니다. 반드시 가장 먼저 수행합니다.

### 0-1. SQL 로깅 활성화

```
목적: 각 API 호출에서 실제로 실행되는 SQL 수와 실행 시간을 확인
```

- [ ] `build.gradle`에 p6spy 의존성 추가
  ```gradle
  implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.0'
  ```
- [ ] `application.yml`에 Hibernate SQL 로깅 설정 추가
  ```yaml
  spring:
    jpa:
      properties:
        hibernate:
          format_sql: true
          use_sql_comments: true
  logging:
    level:
      org.hibernate.SQL: DEBUG
      org.hibernate.type.descriptor.sql.BasicBinder: TRACE
  ```
- [ ] 주요 API 호출 후 로그에서 쿼리 수 카운트
  - [ ] 피드 조회 (`GET /api/posts?feedType=ALL&size=10`) → 쿼리 수 기록: ___개
  - [ ] 게시글 상세 (`GET /api/posts/{id}`) → 쿼리 수 기록: ___개
  - [ ] 부모 댓글 조회 (`GET /api/comments/posts/{id}/comments?size=10`) → 쿼리 수 기록: ___개
  - [ ] 좋아요 (`POST /api/posts/{id}/likes`) → 쿼리 수 기록: ___개

### 0-2. 모니터링 대시보드 구축

```
목적: 실시간으로 서버 상태를 시각화하고 최적화 효과를 수치로 증명
```

- [ ] Actuator 엔드포인트 활성화 확인
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,metrics,prometheus
    metrics:
      tags:
        application: coredisc
  ```
- [ ] Prometheus 설정
  - [ ] `prometheus.yml`에 CoreDisc 스크래핑 타겟 추가
  - [ ] 스크래핑 간격: 15초
  - [ ] `/actuator/prometheus` 접근 확인
- [ ] Grafana 대시보드 구성
  - [ ] JVM 메트릭 패널 (힙 메모리, GC, 스레드)
  - [ ] HTTP 메트릭 패널 (요청 수, 응답 시간 p50/p95/p99, 에러율)
  - [ ] HikariCP 패널 (활성/유휴 커넥션, 대기 시간)
  - [ ] Redis 메트릭 패널 (히트/미스율, 연산 수)
  - [ ] 커스텀 비즈니스 메트릭 패널 (게시글 생성 수, 이미지 업로드 수)
- [ ] 대시보드 스크린샷 저장 (Before 기준선)

### 0-3. 부하 테스트 환경 구축

```
목적: 최적화 전후를 정량적으로 비교할 수 있는 재현 가능한 테스트 환경
```

- [ ] k6 설치 (`brew install k6`)
- [ ] 테스트 데이터 시딩 스크립트 작성
  - [ ] 사용자 100명 생성
  - [ ] 각 사용자별 50명 팔로잉 설정
  - [ ] 각 사용자별 게시글 30개 (답변 4개 + 이미지 2개)
  - [ ] 각 게시글별 댓글 10개 + 대댓글 5개
  - [ ] 좋아요 랜덤 분배
- [ ] k6 시나리오 스크립트 작성
  - [ ] `scripts/k6/feed-load-test.js` — 피드 조회 부하
  - [ ] `scripts/k6/image-upload-test.js` — 이미지 업로드 부하
  - [ ] `scripts/k6/like-concurrency-test.js` — 좋아요 동시성
  - [ ] `scripts/k6/comment-load-test.js` — 댓글 작성/조회 부하
  - [ ] `scripts/k6/mixed-scenario.js` — 실제 사용 패턴 혼합
- [ ] Before 기준선 측정 실행
  - [ ] 피드 조회 (VU 100, 2분) → p95: ___ms, RPS: ___
  - [ ] 이미지 업로드 (VU 50, 2분) → p95: ___ms
  - [ ] 좋아요 동시성 (VU 500, 10초) → p99: ___ms, 정확도: ___%
  - [ ] 댓글 조회 (VU 100, 2분) → p95: ___ms
- [ ] 결과를 `context/LOAD-TEST-RESULTS.md`에 기록

### 0-4. EXPLAIN 분석

```
목적: 현재 쿼리가 인덱스를 어떻게 사용하는지 (또는 사용하지 않는지) 확인
```

- [ ] MySQL 접속하여 주요 쿼리 EXPLAIN 실행
  - [ ] 피드 조회 쿼리 EXPLAIN → type: ___, rows: ___, Extra: ___
  - [ ] 댓글 조회 쿼리 EXPLAIN → type: ___, rows: ___, Extra: ___
  - [ ] 팔로우 서브쿼리 EXPLAIN → type: ___, rows: ___, Extra: ___
  - [ ] TodayQuestion 조회 EXPLAIN → type: ___, rows: ___, Extra: ___
- [ ] 결과 스크린샷/텍스트 저장

---

---

## Phase 1: 기초 체력 — 인덱스 + 트랜잭션

> DB 레벨의 기본 최적화. 코드 변경이 적고 효과가 즉각적입니다.

### 1-1. DB 인덱스 추가

```
목적: Full Table Scan → Index Range Scan 전환
예상 효과: 주요 쿼리 2~5배 속도 개선
```

- [ ] **Post 엔티티** 인덱스 추가
  ```java
  @Table(name = "post", indexes = {
      @Index(name = "idx_post_member_status", columnList = "member_id, status"),
      @Index(name = "idx_post_status_created", columnList = "status, created_at"),
      @Index(name = "idx_post_member_status_created", columnList = "member_id, status, created_at")
  })
  ```
- [ ] **Comment 엔티티** 인덱스 추가
  ```java
  @Table(name = "comment", indexes = {
      @Index(name = "idx_comment_post_depth_id", columnList = "post_id, depth, id"),
      @Index(name = "idx_comment_parent_id", columnList = "parent_id, id")
  })
  ```
- [ ] **Follow 엔티티** 인덱스 + 유니크 추가
  ```java
  @Table(name = "follow",
      uniqueConstraints = @UniqueConstraint(columnNames = {"follower_id", "following_id"}),
      indexes = {
          @Index(name = "idx_follow_follower_circle", columnList = "follower_id, is_circle"),
          @Index(name = "idx_follow_following", columnList = "following_id")
  })
  ```
- [ ] **TodayQuestion 엔티티** 복합 인덱스 추가
  ```java
  @Table(name = "today_question", indexes = {
      @Index(name = "idx_tq_member_order_date", columnList = "member_id, question_order, selected_date")
  })
  ```
- [ ] **Notification 엔티티** 인덱스 추가
  ```java
  @Table(name = "notification", indexes = {
      @Index(name = "idx_notification_receiver_created", columnList = "receiver_id, created_at")
  })
  ```
- [ ] **Device 엔티티** 인덱스 추가
  ```java
  @Table(name = "device", indexes = {
      @Index(name = "idx_device_member_active", columnList = "member_id, is_active")
  })
  ```
- [ ] **Block 엔티티** 유니크 추가
  ```java
  @Table(name = "block",
      uniqueConstraints = @UniqueConstraint(columnNames = {"blocker_id", "blocked_id"}))
  ```
- [ ] 인덱스 적용 후 DDL 실행 확인 (서버 재시작 또는 직접 ALTER TABLE)
- [ ] EXPLAIN 재실행하여 인덱스 사용 확인
  - [ ] 피드 조회: type=ALL → type=ref 전환 확인
  - [ ] 댓글 조회: type=ALL → type=ref 전환 확인

### 1-2. @Transactional(readOnly=true) 누락 보완

```
목적: 읽기 전용 쿼리에서 JPA 더티 체킹 비용 제거
```

- [ ] **PostQueryServiceImpl** 클래스에 추가
  ```java
  @Service
  @Transactional(readOnly = true)
  @RequiredArgsConstructor
  public class PostQueryServiceImpl implements PostQueryService {
  ```
- [ ] **NotificationQueryServiceImpl** 클래스에 추가
  ```java
  @Service
  @Transactional(readOnly = true)
  @RequiredArgsConstructor
  public class NotificationQueryServiceImpl implements NotificationQueryService {
  ```
- [ ] 다른 QueryService 전수 확인 후 누락된 곳 보완
  - [ ] MemberQueryServiceImpl
  - [ ] QuestionQueryServiceImpl
  - [ ] CategoryQueryServiceImpl
  - [ ] CalendarQueryServiceImpl
  - [ ] 기타

### 1-3. Hibernate Batch Fetch Size 설정

```
목적: @OneToMany LAZY 로딩 시 IN 절로 배치 페칭 (N+1 경감)
```

- [ ] `application.yml`에 추가
  ```yaml
  spring:
    jpa:
      properties:
        hibernate:
          default_batch_fetch_size: 100
  ```
- [ ] 효과 확인: 댓글 replies 로딩 시 쿼리 수 감소 확인

### 1-4. HikariCP 커넥션 풀 튜닝

```
목적: 적절한 커넥션 풀 크기로 DB 커넥션 고갈 방지
```

- [ ] `application.yml` 확인 및 설정 추가
  ```yaml
  spring:
    datasource:
      hikari:
        maximum-pool-size: 20
        minimum-idle: 5
        idle-timeout: 30000
        max-lifetime: 1800000
        connection-timeout: 5000
        pool-name: CoreDiscHikariPool
        leak-detection-threshold: 10000
  ```

### Phase 1 검증

- [ ] p6spy 로그로 쿼리 수 재측정
  - [ ] 피드 조회: Before ___개 → After ___개
  - [ ] 댓글 조회: Before ___개 → After ___개
- [ ] EXPLAIN 결과 비교 스크린샷 저장
- [ ] k6 부하 테스트 재실행하여 기준선 대비 개선 확인

---

---

## Phase 2: 쿼리 최적화 — N+1 해결

> 가장 높은 ROI를 가진 최적화. 쿼리 수를 극적으로 줄입니다.

### 2-1. 피드 TodayQuestion N+1 해결

```
목적: 게시글 10개 조회 시 질문 쿼리 10~40개 → 1개로 통합
```

- [ ] `TodayQuestionRepository`에 배치 조회 메서드 추가
  ```java
  List<TodayQuestion> findAllByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
      Collection<Long> memberIds,
      Collection<Integer> questionOrders,
      LocalDate startDate,
      LocalDate endDate
  );
  ```
- [ ] `QueryPostRepositoryImpl`의 피드 조회 로직 수정
  - [ ] 게시글 조회 후 필요한 (memberId, questionOrder, date) 조합 수집
  - [ ] 단일 배치 쿼리로 모든 질문 한 번에 조회
  - [ ] 메모리에서 Map으로 매핑하여 O(1) 조회
- [ ] 게시글 상세 조회 (`findPostDetail`)에도 동일 패턴 적용
  - [ ] 4개 질문을 개별 조회 → 1개 IN 절 배치 조회
- [ ] 쿼리 수 확인: 피드 10건 기준 42개 → 3개

### 2-2. 피드 팔로우 서브쿼리 최적화

```
목적: 동일한 follow 서브쿼리 3회 → 사전 로딩 1회
```

- [ ] 피드 조회 시작 시 팔로잉 ID + 서클 ID를 먼저 조회
  ```java
  // 사전 조회 (1회)
  List<Follow> myFollows = followRepository.findAllByFollowerId(memberId);
  List<Long> followingIds = myFollows.stream().map(f -> f.getFollowing().getId()).toList();
  List<Long> circleIds = myFollows.stream().filter(Follow::isCircle)
      .map(f -> f.getFollowing().getId()).toList();
  ```
- [ ] `findPostFeed()` 메서드에 followingIds, circleIds를 파라미터로 전달
- [ ] 메서드 내부에서 서브쿼리 대신 `.in(followingIds)` 사용
- [ ] 서브쿼리 3회 → 사전 쿼리 1회로 변경 확인

### 2-3. 댓글 hasChild N+1 해결

```
목적: 부모 댓글 10개 조회 시 replies 로딩 10회 → EXISTS 서브쿼리 0회
```

- [ ] `commentQueryRepositoryImpl`에 서브쿼리 포함 메서드 추가
  ```java
  // Projections로 hasChild, replyCount를 쿼리 내에서 계산
  List<CommentWithMeta> findParentCommentsWithMeta(
      Long postId, Long cursorId, int size);
  ```
  - [ ] EXISTS 서브쿼리로 hasReplies 계산 (replies 컬렉션 로딩 없이)
  - [ ] COUNT 서브쿼리로 replyCount 계산
  - [ ] member, profileImg fetchJoin 추가
- [ ] `CommentWithMeta` DTO 클래스 생성 (Comment + hasChild + replyCount)
- [ ] `CommentQueryServiceImpl` 수정: 새 메서드 사용
- [ ] 쿼리 수 확인: 11개 → 1개

### 2-4. 댓글 조회 fetchJoin 추가

```
목적: comment.member 접근 시 N+1 방지
```

- [ ] 부모 댓글 조회에 `.leftJoin(comment.member).fetchJoin()` 추가
- [ ] 대댓글 조회에도 동일 적용
- [ ] `.leftJoin(comment.member.profileImg).fetchJoin()` 추가

### Phase 2 검증

- [ ] p6spy 로그로 쿼리 수 확인
  - [ ] 피드 10건: Before ___개 → After 3개
  - [ ] 댓글 10건: Before ___개 → After 1개
- [ ] k6 부하 테스트
  - [ ] 피드 p95: Before ___ms → After ___ms
  - [ ] 댓글 p95: Before ___ms → After ___ms

---

---

## Phase 3: 비동기 전환

> API 응답 시간에서 외부 I/O(S3, FCM)를 제거합니다.

### 3-1. AsyncConfig 확장

```
목적: S3, FCM, 배치 각각에 전용 스레드풀 생성
```

- [ ] `AsyncConfig.java`에 추가 Executor 등록
  ```java
  @Bean(name = "imageExecutor")
  public ThreadPoolTaskExecutor imageExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(4);
      executor.setMaxPoolSize(8);
      executor.setQueueCapacity(50);
      executor.setThreadNamePrefix("Image-");
      executor.setWaitForTasksToCompleteOnShutdown(true);
      executor.setAwaitTerminationSeconds(30);
      executor.initialize();
      return executor;
  }

  @Bean(name = "notificationExecutor")
  public ThreadPoolTaskExecutor notificationExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(4);
      executor.setMaxPoolSize(10);
      executor.setQueueCapacity(100);
      executor.setThreadNamePrefix("Notification-");
      executor.setWaitForTasksToCompleteOnShutdown(true);
      executor.setAwaitTerminationSeconds(30);
      executor.initialize();
      return executor;
  }
  ```

### 3-2. S3 이미지 병렬 업로드

```
목적: 원본/썸네일 업로드를 병렬화하여 응답 시간 47% 단축
```

- [ ] `AmazonS3Manager`에 병렬 업로드 메서드 추가
  - [ ] MultipartFile을 `byte[]`로 한 번만 읽기
  - [ ] `CompletableFuture.supplyAsync()`로 원본 업로드 비동기 실행
  - [ ] `CompletableFuture.supplyAsync()`로 썸네일 생성+업로드 비동기 실행
  - [ ] `CompletableFuture.allOf().join()`으로 두 작업 완료 대기
- [ ] `imageExecutor` 스레드풀 사용하도록 설정
- [ ] 기존 동기 메서드를 새 병렬 메서드로 교체

### 3-3. S3 I/O를 트랜잭션 외부로 분리

```
목적: S3 I/O 동안 DB 커넥션이 점유되지 않도록 분리
```

- [ ] `PostCommandServiceImpl.updateImageAnswer()` 리팩토링
  - [ ] 1단계: S3 업로드 (트랜잭션 밖)
  - [ ] 2단계: DB 저장 (@Transactional — 짧은 트랜잭션)
  - [ ] 3단계: DB 실패 시 S3 보상 삭제 (catch 블록)
- [ ] `PostCommandServiceImpl.deletePost()` 리팩토링
  - [ ] 1단계: DB에서 S3 키 목록 조회
  - [ ] 2단계: DB 삭제 (@Transactional)
  - [ ] 3단계: S3 삭제 (트랜잭션 밖, 비동기)

### 3-4. 좋아요/댓글 알림 이벤트 기반 비동기 전환

```
목적: 좋아요/댓글 API 응답에서 FCM 전송 시간 완전 제거
```

- [ ] Spring Event 클래스 생성
  - [ ] `PostLikedEvent(postId, likerId, postOwnerId)`
  - [ ] `CommentCreatedEvent(commentId, postId, commenterId, postOwnerId)`
  - [ ] `ReplyCreatedEvent(replyId, parentCommentId, replierId, parentAuthorId)`
- [ ] 이벤트 리스너 생성 (`NotificationEventListener`)
  ```java
  @Component
  @RequiredArgsConstructor
  public class NotificationEventListener {

      @Async("notificationExecutor")
      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void handlePostLiked(PostLikedEvent event) {
          // 알림 생성 + FCM 전송
      }

      @Async("notificationExecutor")
      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void handleCommentCreated(CommentCreatedEvent event) { ... }

      @Async("notificationExecutor")
      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void handleReplyCreated(ReplyCreatedEvent event) { ... }
  }
  ```
- [ ] `PostLikeCommandServiceImpl.createLike()` 수정
  - [ ] 알림/FCM 코드 제거
  - [ ] `applicationEventPublisher.publishEvent(new PostLikedEvent(...))` 추가
- [ ] `CommentCommandServiceImpl.createComment()` 수정
  - [ ] 알림/FCM 코드 제거
  - [ ] `applicationEventPublisher.publishEvent(new CommentCreatedEvent(...))` 추가
- [ ] `CommentCommandServiceImpl.createReply()` 수정
  - [ ] 알림/FCM 코드 제거
  - [ ] `applicationEventPublisher.publishEvent(new ReplyCreatedEvent(...))` 추가
- [ ] FCM 배치 전송 메서드 구현
  - [ ] 디바이스 목록을 한 번에 조회
  - [ ] `FirebaseMessaging.getInstance().sendEach()` 또는 병렬 스트림 사용

### 3-5. 좋아요 카운트 — Redis 원자적 연산

```
목적: likeCount/commentCount 실시간 관리 + Lost Update 방지
```

- [ ] `RedisUtil`에 increment/decrement 메서드 추가
  ```java
  public Long increment(String key) {
      return redisTemplate.opsForValue().increment(key);
  }
  public Long decrement(String key) {
      return redisTemplate.opsForValue().decrement(key);
  }
  ```
- [ ] `PostLikeCommandServiceImpl.createLike()` 수정
  - [ ] DB 저장 후 `redisUtil.increment("post:like:" + postId)` 추가
- [ ] `PostLikeCommandServiceImpl.deleteLike()` 수정
  - [ ] DB 삭제 후 `redisUtil.decrement("post:like:" + postId)` 추가
- [ ] 댓글에도 동일 패턴 적용
  - [ ] `redisUtil.increment("post:comment:" + postId)`
  - [ ] `redisUtil.decrement("post:comment:" + postId)`
- [ ] 피드/상세 조회에서 Redis 카운트 사용하도록 수정
- [ ] Redis → DB 동기화 배치 스케줄러 추가 (1분~5분 주기)
  ```java
  @Scheduled(fixedRate = 60_000)
  public void syncCountsToDatabase() { ... }
  ```

### Phase 3 검증

- [ ] k6 부하 테스트
  - [ ] 이미지 업로드 p95: Before ___ms → After ___ms
  - [ ] 좋아요 p95: Before ___ms → After ___ms
  - [ ] 댓글 작성 p95: Before ___ms → After ___ms
- [ ] HikariCP 메트릭 확인: 활성 커넥션 수 감소 확인
- [ ] 좋아요 동시성 테스트: 500명 → 카운트 정확도 확인

---

---

## Phase 4: 캐싱 레이어

> 반복적으로 조회되는 데이터를 메모리에 캐싱합니다.

### 4-1. Caffeine + Spring Cache 설정

```
목적: 로컬 캐시로 Redis 접근조차 줄임
```

- [ ] `build.gradle`에 의존성 추가
  ```gradle
  implementation 'org.springframework.boot:spring-boot-starter-cache'
  implementation 'com.github.ben-manes.caffeine:caffeine'
  ```
- [ ] `CacheConfig.java` 생성
  ```java
  @Configuration
  @EnableCaching
  public class CacheConfig {

      @Bean
      public CaffeineCacheManager caffeineCacheManager() {
          CaffeineCacheManager manager = new CaffeineCacheManager();
          manager.setCaffeine(Caffeine.newBuilder()
              .maximumSize(1000)
              .expireAfterWrite(5, TimeUnit.MINUTES)
              .recordStats()  // 캐시 통계 수집 (모니터링용)
          );
          return manager;
      }
  }
  ```

### 4-2. 팔로잉/서클 ID 목록 캐싱

```
목적: 피드 조회마다 반복되는 팔로우 쿼리 제거
캐시 키: "followingIds:{memberId}", "circleIds:{memberId}"
TTL: 5분 + 팔로우/언팔로우 시 무효화
```

- [ ] `FollowQueryService`에 캐시 적용
  ```java
  @Cacheable(value = "followingIds", key = "#memberId")
  public List<Long> getFollowingIds(Long memberId) { ... }

  @Cacheable(value = "circleIds", key = "#memberId")
  public List<Long> getCircleIds(Long memberId) { ... }
  ```
- [ ] `FollowCommandService`에 캐시 무효화 적용
  ```java
  @CacheEvict(value = {"followingIds", "circleIds"}, key = "#memberId")
  public void follow(Long memberId, Long targetId) { ... }

  @CacheEvict(value = {"followingIds", "circleIds"}, key = "#memberId")
  public void unfollow(Long memberId, Long targetId) { ... }
  ```

### 4-3. 공식 질문 캐싱

```
목적: 거의 변하지 않는 질문 내용을 메모리에 캐싱
캐시 키: "officialQuestion:{id}"
TTL: 24시간
```

- [ ] `QuestionQueryService`에 캐시 적용
  ```java
  @Cacheable(value = "officialQuestion", key = "#questionId")
  public OfficialQuestion getOfficialQuestion(Long questionId) { ... }
  ```

### 4-4. 게시글 좋아요 수 Redis 캐싱 (Phase 3에서 구현된 것 활용)

```
이미 Phase 3-5에서 Redis에 카운트를 저장하고 있으므로,
피드/상세 조회에서 DB COUNT 대신 Redis GET 사용
```

- [ ] 피드 조회 시 `redisUtil.get("post:like:" + postId)`로 카운트 조회
- [ ] 캐시에 없으면 DB에서 COUNT 후 Redis에 저장

### Phase 4 검증

- [ ] Redis 히트율 모니터링 (`INFO stats` 또는 Grafana)
  - [ ] 목표: 히트율 80%+
- [ ] k6 부하 테스트
  - [ ] 피드 p95: Phase 2 결과 대비 추가 개선 확인
- [ ] Caffeine 캐시 통계 확인 (recordStats 활용)

---

---

## Phase 5: 배치 최적화

> 배치 작업의 메모리 효율성과 실행 시간을 개선합니다.

### 5-1. 임시 게시글 정리 — 청크 기반 처리

```
목적: OOM 방지 + S3 Batch Delete로 실행 시간 99% 단축
```

- [ ] `PostRepository`에 청크 조회 메서드 추가
  ```java
  List<Post> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
      PostStatus status, LocalDateTime cutoff);
  ```
- [ ] `PostCommandServiceImpl.cleanupOldTempPosts()` 리팩토링
  - [ ] while 루프로 100건씩 청크 처리
  - [ ] 각 청크의 S3 키를 수집
  - [ ] S3 Batch Delete API로 한 번에 삭제
  - [ ] JPQL 벌크 DELETE로 DB 일괄 삭제
  - [ ] 각 청크를 독립 트랜잭션으로 처리 (`REQUIRES_NEW`)
- [ ] S3 Batch Delete 메서드 추가 (`AmazonS3Manager`)
  ```java
  public void deleteObjectsBatch(List<String> s3Keys) {
      Lists.partition(s3Keys, 1000).forEach(batch -> {
          DeleteObjectsRequest request = new DeleteObjectsRequest(bucket)
              .withKeys(batch.stream()
                  .map(DeleteObjectsRequest.KeyVersion::new)
                  .toList());
          amazonS3.deleteObjects(request);
      });
  }
  ```

### 5-2. 배치 스케줄러 병렬 실행

```
목적: 독립적인 통계 생성 작업을 병렬로 실행
```

- [ ] `BatchScheduler.processDailyStatistics()` 수정
  ```java
  @Scheduled(cron = "0 0 0 * * *")
  public void processDailyStatistics() {
      // 1. 임시 게시글 정리 (먼저 실행)
      postCommandService.cleanupOldTempPosts(cutoffDate);

      // 2. 4개 통계 작업 병렬 실행
      CompletableFuture.allOf(
          CompletableFuture.runAsync(() -> reportStatBatchService.generateDailyStatistics(date)),
          CompletableFuture.runAsync(() -> reportStatBatchService.generateMonthlyFixedQuestionStats(date)),
          CompletableFuture.runAsync(() -> reportStatBatchService.generateRandomQuestionsStats(date)),
          CompletableFuture.runAsync(() -> reportStatBatchService.generateMonthlySelectionDiaryStats(date))
      ).join();
  }
  ```

### 5-3. 알림 배치 FCM 전송 최적화

```
목적: NotificationScheduler의 FCM 전송 시간 단축
```

- [ ] 전체 대상자 조회 → 디바이스 일괄 조회 (IN 절)
- [ ] `FirebaseMessaging.sendEach()` 또는 `sendAll()` 배치 API 사용
- [ ] 500건씩 배치 분할 전송

### Phase 5 검증

- [ ] 배치 실행 시간 측정
  - [ ] 임시 게시글 정리 (1000건): Before ___초 → After ___초
  - [ ] 통계 생성: Before ___초 → After ___초
  - [ ] 알림 스케줄러: Before ___초 → After ___초

---

---

## Phase 6: 최종 검증 — Before/After 비교

> 모든 최적화가 완료된 후 최종 부하 테스트를 실행합니다.

### 6-1. 최종 부하 테스트 실행

- [ ] Phase 0과 동일한 조건으로 k6 테스트 재실행
  - [ ] 피드 조회 (VU 100, 2분)
  - [ ] 이미지 업로드 (VU 50, 2분)
  - [ ] 좋아요 동시성 (VU 500, 10초)
  - [ ] 댓글 조회 (VU 100, 2분)
  - [ ] 혼합 시나리오 (VU 200, 5분)

### 6-2. Before/After 결과표 작성

```markdown
| API | Before p95 | After p95 | 쿼리 수 Before | 쿼리 수 After | 개선율 |
|-----|-----------|-----------|--------------|-------------|--------|
| 피드 조회 (10건) | ___ms | ___ms | ___개 | 3개 | __% |
| 이미지 업로드 | ___ms | ___ms | ___개 | ___개 | __% |
| 좋아요 | ___ms | ___ms | ___개 | ___개 | __% |
| 댓글 작성 | ___ms | ___ms | ___개 | ___개 | __% |
| 댓글 조회 (10건) | ___ms | ___ms | ___개 | 1개 | __% |
| 배치 정리 (1000건) | ___초 | ___초 | ___개 | ___개 | __% |
```

### 6-3. Grafana 대시보드 스크린샷

- [ ] Before 대시보드 스크린샷 (Phase 0에서 저장한 것)
- [ ] After 대시보드 스크린샷
- [ ] 비교 이미지 생성 (나란히 배치)

### 6-4. 최종 문서화

- [ ] `context/AFTER-STATE.md` 작성 (최적화 후 상태 정리)
- [ ] `context/LOAD-TEST-RESULTS.md` 작성 (Before/After 수치)
- [ ] `PORTFOLIO.md` 업데이트 (최적화 섹션 수치 반영)

---

---

## 작업 예상 일정

| Phase | 예상 소요 | 난이도 | 선행 조건 |
|-------|----------|:---:|------|
| Phase 0 (측정 환경) | 1~2일 | ★★☆ | 없음 |
| Phase 1 (인덱스/트랜잭션) | 1일 | ★☆☆ | Phase 0 |
| Phase 2 (쿼리 최적화) | 2~3일 | ★★★ | Phase 1 |
| Phase 3 (비동기 전환) | 3~4일 | ★★★★ | Phase 2 |
| Phase 4 (캐싱) | 1~2일 | ★★☆ | Phase 3 |
| Phase 5 (배치 최적화) | 1~2일 | ★★☆ | Phase 3 |
| Phase 6 (최종 검증) | 1일 | ★☆☆ | 전체 완료 |
| **합계** | **10~15일** | | |

---

## 주의사항

1. **반드시 Phase 0부터** — 측정 없이 최적화하면 "감"에 의존하게 됨
2. **각 Phase 완료 후 중간 측정** — 어떤 최적화가 얼마나 효과 있었는지 구분 가능
3. **하나씩 적용 후 테스트** — 여러 변경을 동시에 하면 문제 발생 시 원인 특정 어려움
4. **develop 브랜치에서 feature 분기** — `feature/optimize-feed-n+1` 같은 세분화된 브랜치 사용
5. **기존 테스트 통과 확인** — 최적화 중 기존 동작이 깨지지 않는지 확인
