# CoreDisc 최적화 진행 현황

> 이 문서는 세션이 끊겨도 다음 세션에서 현재 진행도를 파악할 수 있도록 관리합니다.
> 최적화 작업이 끝날 때마다 반드시 업데이트합니다.

---

## 현재 상태

- **현재 Phase**: Phase 9 — 알림 파이프라인 장애 복원력 ✅ **완료**
- **이전 작업**: Phase 8 — Redis 장애 복원력 + 핫 키 최적화 완료
- **마지막 업데이트**: 2026-03-22

### Phase 9: 알림 파이프라인 장애 복원력 — ✅ 완료
- [x] 9-1a. FcmServiceStub 지연 시뮬레이션 활성화 (stub.delay-ms: 200)
- [x] 9-1b. k6 알림 스트레스 테스트 작성 (`k6/notification-stress-test.js`)
- [x] 9-2a. CallerRunsPolicy + 큐 용량 증가 (100 → 500)
- [x] 9-2b. FcmCircuitBreaker 생성 (FAILURE_THRESHOLD=5, OPEN_DURATION=30초)
- [x] 9-2c. 이중 토큰 검증 제거 + CB 적용 (기기당 FCM 2회 → 1회)
- [x] 9-2d. 이벤트 리스너 재시도 (1회 retry, 100ms backoff)
- [x] 9-2e. Micrometer 메트릭 추가 (6종: duration, success, failure, retry, skip, circuit.state)
- [x] 9-2f. 스케줄러 비동기화 (notificationExecutor 위임)
- [x] 9-3. 외부 큐 ADR — Redis Streams 도입하지 않음 (`docs/optimization/23-notification-queue-adr.md`)
- [x] 9-4. SKIP (외부 큐 미채택)
- [x] 9-5. **Story 1 — 코드 최적화** (동일 인프라 e2-standard-2, 단일 VM)
  - [x] Before: p95=3.83s, 48 req/s → After: p95=3.06s, 58 req/s
  - [x] **레이턴시 20% 개선, 처리량 19% 향상, HTTP 500 = 0**
  - [x] Prometheus 메트릭 7종 정상 등록
- [x] 9-6. **Story 2 — 인프라 분리** (동일 코드, 인프라만 변경)
  - [x] 변경: e2-standard-2→4 (4vCPU) + Redis 전용 VM 분리
  - [x] 알림 스트레스: p95 3.06s → **421ms (↓86%)**, 처리량 58 → **274 req/s (↑375%)**
  - [x] 통합 부하: p95 2.96s → **226ms (↓92%)**, 처리량 71 → **174 req/s (↑144%)**
  - [x] **병목 = 인프라 (코드 아님)를 측정으로 증명**
- [x] 9-7. 수용 능력 분석: **쾌적 DAU 10K~15K / MAU 50K~75K**
- [x] 9-8. 문서 업데이트 (`docs/optimization/22-phase9-notification-resilience.md`)

### Phase 8: Redis 장애 복원력 + 핫 키 최적화 — ✅ 완료
- [x] 8-1. Redis Timeout + Circuit Breaker 장애 복원력 (RedisCircuitBreaker, 300ms timeout)
- [x] 8-2. Fan-out 청크 단위 Retry + 캐시 evict 분리 (500명 청크, 2회 재시도, 셀럽 5000명 스킵)
- [x] 8-3. Micrometer 커스텀 메트릭 + Grafana 대시보드 (7패널, 10개 메트릭)
- [x] 8-4. Block/Unfollow 즉시 캐시 무효화 (동기 evict)
- [x] 8-5. 성능 측정 (GCP, 2026-03-21)
  - [x] 핫 키 스트레스 테스트: 100VU, detail p95=4.08s, like p95=3.71s (DB 락 contention)
  - [x] 피드 확장성 테스트: 90VU, p95=628ms, avg=106ms, **에러 0%**, 팔로잉 수 무관 균일 성능
  - [x] 통합 부하 테스트: 180VU, http_req p95=2.96s, 71.2 req/s, 46,789 요청
  - [x] **Redis 장애 복원력: Redis kill 중 피드 HTTP 200 + 신규 로그인 성공 — 전체 서비스 무중단**
  - [x] 캐시 무효화: 코드 수준 동기 evict 확인 (Block 양방향, Unfollow 단방향)
  - [x] Prometheus 경로 분포: cache 59.5%, inbox 36.7%, pull 3.7%
- [x] 8-6. 문서 수치 반영 완성 (`docs/optimization/21-phase8-resilience-hotkey.md`)
- [x] 8-7. RedisUtil graceful fallback — 인증 레이어 Redis 장애 내성 추가

### Phase 7: Push/Pull 하이브리드 피드 — ✅ 완료
- [x] 7-1. Redis Sorted Set 기반 피드 인박스 (FeedInboxService)
- [x] 7-2. Fan-out on Write (PostPublishedEvent → FeedFanoutEventListener)
- [x] 7-3. 인박스 우선 읽기 + DTO 캐시 + Pull fallback (FeedReadService)
- [x] 7-4. Edge Case 처리 (삭제/Block/Unfollow/Follow/Circle 인박스 정리)
- [x] 7-5. 인박스 워밍업 (FeedInboxWarmupRunner)
- [x] 7-6. 부하 테스트 검증
  - Ramp test (500VU): **p95 2.41s → 1.60s (33.6% 개선), throughput 122→135 req/s (10.7% 증가)**
  - Feed-by-following (90VU): **p95 2.2s → 522ms (76.3% 개선), avg 312ms→78ms (75% 개선)**
  - 팔로잉 5000명: **p95 2.56s → 504ms (80.3% 개선)**
- [x] 7-7. 문서화 (`docs/optimization/20-hybrid-feed-architecture.md`)

---

## Phase 진행 체크리스트

### Phase 0: 측정 환경 구축
- [x] **0-1. SQL 로깅 활성화** — p6spy 설정, 주요 API별 쿼리 수 측정 (완료 2026-02-20)
- [ ] **0-2. 모니터링 대시보드** — Actuator + Prometheus + Grafana
- [x] **0-3. 부하 테스트 환경 구축** — k6 설치, 시드 데이터 918MB, 테스트 스크립트 작성
- [x] **0-4. EXPLAIN 분석** — 주요 쿼리 실행 계획 분석 (완료 2026-02-20)

### Phase 1: 기초 체력 (인덱스 + 트랜잭션) — ✅ 완료
- [x] 1-1. DB 인덱스 추가 (TodayQuestion, Post, Comment, Follow, Device) (완료 2026-02-20)
- [x] 1-2. @Transactional(readOnly=true) 보완 — PostQueryServiceImpl (완료 2026-02-20)
- [x] 1-3. Hibernate Batch Fetch Size 100 설정 (완료 2026-02-20)
- [x] 1-4. HikariCP 커넥션 풀 튜닝 (완료 2026-02-20)
- [x] Phase 1 검증 — **p95 518ms → 266ms (-48.6%), 처리량 86→93 req/s (+8.1%)**

### Phase 2: 쿼리 최적화 (N+1 해결) — ✅ 완료
- [x] 2-1. 피드 TodayQuestion N+1 해결 — 배치 쿼리 + 룩업 맵 + EntityGraph (완료 2026-02-20)
- [x] 2-2. 피드 팔로우 서브쿼리 최적화 — Phase 1 인덱스로 이미 해결 (완료 2026-02-20)
- [x] 2-3. 댓글 hasChild/replyCount N+1 해결 — GROUP BY 배치 쿼리 (완료 2026-02-20)
- [x] 2-4. 댓글 조회 fetchJoin 추가 — member + profileImg (완료 2026-02-20)
- [x] Phase 2 검증 — **Feed 쿼리 16→7 (-56%), 댓글 쿼리 ~30→4 (-87%)**

### Phase 3: 비동기 전환 — ✅ 완료 (피드+댓글 범위)
- [x] 3-1. AsyncConfig 확장 — notificationExecutor 추가 (완료 2026-02-20)
- [ ] 3-2. S3 이미지 병렬 업로드 (범위 외 — S3 인프라)
- [ ] 3-3. S3 I/O 트랜잭션 외부 분리 (범위 외 — S3 인프라)
- [x] 3-4. 좋아요/댓글 알림 이벤트 기반 비동기 전환 (완료 2026-02-20)
- [ ] 3-5. 좋아요 카운트 Redis 원자적 연산 (추후)
- [x] Phase 3 검증 — **http_req p95 317ms → 270ms, Feed p95 290ms → 262ms**

### Phase 4: 캐싱 레이어 — ✅ 완료
- [x] 4-1. Caffeine + Spring Cache 설정 (완료 2026-02-20)
- [x] 4-2. 팔로잉/서클 ID 목록 캐싱 (완료 2026-02-20)
- [x] 4-3. 질문 컨텐츠 배치 쿼리 최적화 — 4개 개별 쿼리 → 1개 배치 쿼리 (완료 2026-02-20)
- [x] 4-4. 좋아요 체크 쿼리 최적화 — 엔티티 로딩 3 쿼리 → ID 기반 1 쿼리 (완료 2026-02-20)
- [x] Phase 4 검증 — Feed: Cache HIT 시 follow 쿼리 0회 / 상세 조회: 쿼리 ~10+ → 6 (-40%)

### Phase 5: 배치 최적화 — ✅ 완료
- [x] 5-1. 임시 게시글 정리 청크 기반 처리 — 100건 단위 페이지네이션 (완료 2026-02-20)
- [x] 5-2. 일일 배치 스케줄러 병렬 실행 — CompletableFuture + batchExecutor (완료 2026-02-20)
- [x] 5-3. 리마인더 스케줄러 N+1 쿼리 최적화 — 멤버당 4~8 쿼리 → 전체 2 배치 쿼리 (완료 2026-02-20)
- [x] Phase 5 검증 — 컴파일 성공, API 정상 동작 확인

### Phase 6: 최종 검증 — ✅ 완료
- [x] 6-1. 최종 부하 테스트 — 7개 시나리오 100 VUs 3m30s 실행 (완료 2026-02-20)
- [x] 6-2. 결과표 작성 — Phase별 비교 테이블 + 쿼리 수 개선 테이블 (완료 2026-02-20)
- [ ] 6-3. Grafana 대시보드 — 미구축 (Prometheus/Grafana 설정 없음, 로컬 환경 한계)
- [x] 6-4. 최종 문서화 — 08-phase6-final.md 작성 (완료 2026-02-20)

---

## 완료된 작업 상세

### Phase 0-3: 부하 테스트 환경 구축 (완료 2026-02-20)

**구축 내용:**
- k6 설치 완료
- `k6/generate_seed_data.py` — 대량 데이터 생성 스크립트 (918MB)
- `k6/load-test.js` — 메인 부하 테스트 (5개 시나리오, ramping-vus 100 VUs)
- `k6/smoke-test.js` — 스모크 테스트 (1 VU)
- `k6/seed.sql` — 기본 시드 데이터 (약관, 기본 프로필 이미지)

**Baseline 측정 결과 (918MB 데이터, 100 VUs, 3분 30초):**

| 시나리오 | 평균 | 중위값 | p95 | 최대 |
|----------|------|--------|-----|------|
| Feed | 117ms | 40ms | 489ms | 1.95s |
| Profile | 87ms | 12ms | 438ms | 1.77s |
| Notifications | 88ms | 14ms | 439ms | 1.41s |
| Questions | 98ms | 32ms | 419ms | 2.07s |
| Search | 100ms | 20ms | 464ms | 1.58s |

| 전체 지표 | 값 |
|-----------|------|
| 에러율 | 0.01% |
| 총 요청 | 20,269건 |
| 처리량 | 86 req/s |
| http_req_duration p95 | 518ms |

### Phase 0-1: SQL 로깅 & 쿼리 수 측정 (완료 2026-02-20)

**설정:** p6spy 의존성 추가 + Hibernate SQL 로깅 활성화

**API별 쿼리 수 (p6spy statement 기준):**

| API | 쿼리 수 | 심각도 |
|-----|---------|--------|
| 피드 ALL (10건) | 16 | 높음 (N+1) |
| 피드 CORE (10건) | 17 | 높음 (N+1) |
| 내 프로필 | 8 | 중간 |
| 내 포스트 | 6 | 낮음 |
| 다른 유저 프로필 | 13 | 중간 |
| 안읽은 알림 | 5 | 낮음 |
| 알림 목록 (10건) | 16 | 높음 (N+1) |
| 질문 목록 (카테고리) | 20 | 높음 (N+1) |
| 인기 질문 | 9 | 중간 |
| 선택된 질문 | 8 | 중간 |
| 카테고리 목록 | 5 | 낮음 |
| 멤버 검색 | 16 | 높음 (N+1) |
| **팔로워 목록** | **26** | **심각 (N+1)** |
| 팔로잉 목록 | 16 | 높음 (N+1) |

상세: `docs/optimization/01-sql-logging-query-analysis.md`

**트러블슈팅 (Phase 0 중 해결):**
- TodayQuestion non-unique query 버그 수정 (`findBy` → `findFirstBy`)
- PostQueryServiceImpl `.get()` → `.ifPresent()` 안전 처리
- NotificationReminderScheduler 아키텍처 위반 수정 (JPA 직접 참조 → 도메인 인터페이스)
- 상세: `docs/troubleshooting/02-today-question-non-unique-query.md`

### Phase 1: 기초 체력 (인덱스 + 트랜잭션) (완료 2026-02-20)

**작업 내용:**
- 5개 테이블 7개 복합 인덱스 추가 (TodayQuestion, Post, Comment, Follow, Device)
- PostQueryServiceImpl `@Transactional(readOnly = true)` 추가
- Hibernate `default_batch_fetch_size: 100` 설정
- HikariCP 커넥션 풀 튜닝 (max:20, min-idle:10, timeout:5s)

**EXPLAIN 개선:**

| 쿼리 | Before | After |
|------|--------|-------|
| TodayQuestion | rows:212, filtered:1.11% | rows:1, filtered:100% |
| Post 피드 | rows:20, filtered:50% | rows:100, filtered:100% |
| Comment 댓글 | rows:3, filtered:10% | rows:1, filtered:100% |
| Follow 서클 | rows:20, filtered:50% | rows:2, filtered:100% |

**부하 테스트 결과:**

| 지표 | Baseline | Phase 1 | 개선율 |
|------|----------|---------|--------|
| http_req_duration p95 | 518ms | 266ms | -48.6% |
| Feed p95 | 489ms | 245ms | -49.9% |
| 처리량 | 86 req/s | 93 req/s | +8.1% |
| 총 요청 | 20,269 | 22,075 | +8.9% |

상세: `docs/optimization/03-phase1-indexes-transactions.md`

### Phase 2: 쿼리 최적화 (N+1 해결) (완료 2026-02-20)

**작업 내용:**
- 피드 TodayQuestion: 루프 내 개별 쿼리 → 1회 배치 쿼리 + 룩업 맵 + @EntityGraph
- 댓글 hasChild/replyCount: replies 컬렉션 lazy loading → GROUP BY 배치 쿼리
- 댓글 fetchJoin: member + profileImg fetch join 추가

**쿼리 수 개선:**

| API | Before | After | 감소율 |
|-----|--------|-------|--------|
| Feed ALL (10건) | 16 | 7 | -56% |
| Feed CORE (10건) | 17 | 7 | -59% |
| 부모 댓글 (10건) | ~30+ | 4 | -87% |

**부하 테스트 결과:**

| 지표 | Phase 1 | Phase 2 |
|------|---------|---------|
| Feed p95 | 245ms | 290ms |
| http_req_duration p95 | 266ms | 317ms |
| 처리량 | 93 req/s | 93 req/s |

> 쿼리 수 56~87% 감소가 핵심 성과. p95는 유사 수준 (배치 쿼리 오버헤드와 N+1 제거가 상쇄). DB 커넥션 점유 시간 감소로 고부하 시 더 큰 효과 예상.

상세: `docs/optimization/04-phase2-n-plus-one.md`

### Phase 3: 비동기 전환 (완료 2026-02-20)

**작업 내용:**
- AsyncConfig에 notificationExecutor (core:4, max:10, queue:100) 추가
- NotificationEvent + NotificationEventListener 생성
- PostLikeCommandServiceImpl: 동기 알림/FCM → 이벤트 발행 (의존성 5→3개)
- CommentCommandServiceImpl: 동기 알림/FCM → 이벤트 발행 (의존성 6→4개)
- @TransactionalEventListener(AFTER_COMMIT) + @Async로 완전 비동기 처리

**부하 테스트 결과:**

| 지표 | Phase 2 | Phase 3 |
|------|---------|---------|
| Feed p95 | 290ms | 262ms |
| http_req_duration p95 | 317ms | 270ms |
| 처리량 | 93 req/s | 94 req/s |

상세: `docs/optimization/05-phase3-async-events.md`

### Phase 4: 캐싱 레이어 (완료 2026-02-20)

**작업 내용:**

*4-1/4-2: 피드 팔로우 ID 캐싱*
- Caffeine 로컬 캐시 도입 (spring-boot-starter-cache + caffeine)
- 팔로잉/서클 ID 목록 @Cacheable 캐싱 (TTL 5분, 최대 5000 엔트리)
- 피드 쿼리 서브쿼리 제거 → IN 절 교체
- @CacheEvict로 팔로우/언팔로우/서클 변경 시 캐시 무효화

*4-3: 질문 컨텐츠 배치 쿼리 최적화*
- `findQuestionContent()`: 4개 개별 쿼리 → 1개 배치 쿼리 + 메모리 필터링
- 기존 @EntityGraph 배치 메서드 재활용 (officialQuestion/personalQuestion 즉시 로딩)

*4-4: 좋아요 체크 쿼리 최적화*
- `checkIsLiked()`: Member/Post 엔티티 로딩 3 쿼리 → ID 기반 EXISTS 1 쿼리
- `existsByMemberIdAndPostId` 메서드 추가 (JPA 네이밍 컨벤션)
- PostQueryServiceImpl에서 MemberRepository 의존성 제거

**쿼리 수 개선:**

| 대상 | Before | After | 감소율 |
|------|--------|-------|--------|
| 피드 (Cache MISS) | 7 (서브쿼리) | 9 | - |
| 피드 (Cache HIT) | 7 (서브쿼리) | 7 (IN절) | follow 접근 0회 |
| 상세 조회 질문 | 4 쿼리 | 1 쿼리 | -75% |
| 상세 조회 좋아요 | 3 쿼리 | 1 쿼리 | -67% |
| **상세 조회 전체** | **~10+ 쿼리** | **~6 쿼리** | **-40%** |

**부하 테스트 결과 (4-2 기준):**

| 지표 | Phase 3 | Phase 4 |
|------|---------|---------|
| Feed p95 | 262ms | 246ms |
| http_req_duration p95 | 270ms | 296ms |
| 처리량 | 94 req/s | 94 req/s |

> 4-3/4-4는 게시글 상세 조회에 국한된 최적화로, 단건 쿼리 수 검증으로 효과 확인. 로컬 환경 부하 테스트에서는 리소스 경합으로 유의미한 p95 차이를 측정하기 어려움.

상세: `docs/optimization/06-phase4-caching.md`

### Phase 5: 배치 최적화 (완료 2026-02-20)

**작업 내용:**

*5-1: 임시 게시글 정리 청크 기반 처리*
- `cleanupOldTempPosts()`: 전체 로딩 → 100건 단위 Page 기반 처리
- 삭제 시 항상 page=0으로 조회 (삭제 후 데이터가 당겨오므로)
- 실패 건은 스킵하고 계속 처리 (기존: 실패 시 전체 중단)

*5-2: 일일 배치 스케줄러 병렬 실행*
- `AsyncConfig`에 `batchExecutor` 스레드풀 추가 (core:4, max:8, queue:20)
- `BatchScheduler.runDailyBatch()`: 4개 통계 작업 순차 → CompletableFuture.allOf() 병렬
- 각 작업 독립적 에러 핸들링 (exceptionally)

*5-3: 리마인더 스케줄러 N+1 쿼리 최적화*
- `NotificationReminderScheduler`: 5분마다 실행, 가장 빈도 높은 스케줄러
- Before: 멤버당 `hasTodayQuestions()` 4쿼리 + `hasUnansweredQuestions()` 8쿼리
- After: 전체 멤버 대상 TodayQuestion 배치 쿼리 1회 + PostAnswer 배치 쿼리 1회
- 50명 매칭 시 기존 ~600 쿼리 → **2 쿼리** + 메모리 룩업

**쿼리 수 개선 (리마인더 스케줄러, N명 매칭 기준):**

| 항목 | Before | After |
|------|--------|-------|
| TodayQuestion 조회 | N × 4 쿼리 | **1 배치 쿼리** |
| PostAnswer 존재 확인 | N × 4 쿼리 | **1 배치 쿼리** |
| 총 쿼리 수 (N=50) | ~600 | **2** |

**변경 파일:**

| 파일 | 작업 |
|------|------|
| `AsyncConfig.java` | `batchExecutor` 스레드풀 추가 |
| `BatchScheduler.java` | CompletableFuture 병렬 실행 |
| `PostCommandServiceImpl.java` | 청크 기반 페이지네이션 |
| `JpaPostRepository.java` | 페이지네이션 메서드 추가 |
| `PostRepository.java` (도메인) | `findTempPostsPageable` 추가 |
| `PostRepositoryAdaptor.java` | 구현 추가 |
| `NotificationReminderScheduler.java` | 배치 쿼리 기반 리팩토링 |
| `JpaPostAnswerRepository.java` | 배치 답변 순서 조회 JPQL 추가 |
| `PostAnswerRepository.java` (도메인) | 배치 메서드 추가 |
| `PostAnswerRepositoryAdaptor.java` | 구현 추가 |

상세: `docs/optimization/07-phase5-batch.md`

### Phase 8: Redis 장애 복원력 + 핫 키 최적화 (코드 완료 2026-03-19, 측정 진행 중)

**커밋 이력:**
- `4634f70` (2026-03-17) — Phase 8-1: Redis Timeout + Circuit Breaker 장애 복원력
- `fe715cc` (2026-03-19) — Phase 8-2: Fan-out 청크 단위 Retry + 캐시 evict 분리
- `d8de220` (2026-03-19) — Phase 8-3: Micrometer 커스텀 메트릭 + Grafana 대시보드
- `e0b755e` (2026-03-19) — Phase 8-4: Block/Unfollow 즉시 캐시 무효화

**작업 내용:**

*8-1: Redis Timeout + Circuit Breaker*
- Lettuce commandTimeout 300ms + `REJECT_COMMANDS` (연결 끊김 즉시 거부)
- 수동 Circuit Breaker: CLOSED→OPEN (5회 연속 실패), HALF_OPEN (10초 후), CLOSED (1회 성공)
- FeedCacheService/FeedInboxService 전 메서드에 Circuit Breaker 연동
- Redis 장애 시 Pull fallback으로 서비스 무중단

*8-2: Chunked Fan-out + Retry*
- 팔로워 500명 단위 청크 분할 (기존: 전체 한 번에 Pipeline)
- 청크당 최대 2회 재시도 (100ms backoff)
- 셀럽 threshold: 팔로워 5,000명 초과 시 Fan-out 스킵 → Pull fallback
- 캐시 evict를 인박스 ZADD와 분리 (개별 try-catch)

*8-3: Micrometer 커스텀 메트릭 + Grafana 대시보드*
- 피드 경로별 메트릭: `feed.request` (Counter), `feed.duration` (Timer) — path=cache|inbox|pull
- Fan-out 메트릭: `feed.fanout.duration`, `feed.fanout.retry`, `feed.fanout.failure`
- Circuit Breaker 상태: `redis.circuit.state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
- Caffeine 캐시 → Prometheus 자동 바인딩 (CacheMetricsConfig)
- 4개 스레드풀 ExecutorServiceMetrics 바인딩
- Grafana 대시보드 JSON 7패널 (`infra/grafana-dashboard-feed.json`)

*8-4: Block/Unfollow 즉시 캐시 무효화*
- Block 시: 양방향 feedCacheService.evict() 동기 호출 (blocker + blocked)
- Unfollow 시: 단방향 feedCacheService.evict() 동기 호출 (unfollower)
- 기존 TTL 30초 stale 노출 → 즉시 반영 (Redis DEL 1~2ms)

**신규 생성 파일 (8개):**

| 파일 | 역할 |
|------|------|
| `RedisCircuitBreaker.java` | 수동 Circuit Breaker (84줄) |
| `FeedCacheService.java` | DTO 캐시 + 분산 락 + 즉시 evict |
| `FeedInboxService.java` | Redis Sorted Set 인박스 CRUD |
| `FeedReadService.java` | 3-path 피드 읽기 |
| `FeedFanoutEventListener.java` | 셀럽 스킵 + 청크 retry |
| `FeedCleanupEventListener.java` | 삭제/Block/Unfollow 인박스 정리 |
| `CacheMetricsConfig.java` | Caffeine → Prometheus 바인딩 |
| `grafana-dashboard-feed.json` | 7패널 대시보드 정의 |

**수정 파일 (4개):**

| 파일 | 변경 |
|------|------|
| `RedisConfig.java` | commandTimeout 300ms + REJECT_COMMANDS |
| `AsyncConfig.java` | 4개 Executor Micrometer 메트릭 바인딩 |
| `BlockCommandServiceImpl.java` | 동기 feedCacheService.evict() 추가 |
| `FollowCommandServiceImpl.java` | 동기 feedCacheService.evict() 추가 |

**부하 테스트 결과 (2026-03-21, GCP):**

| 테스트 | 주요 지표 | 결과 |
|--------|---------|------|
| 핫 키 (100VU, 2min) | detail p95 / like p95 / 에러율 | 4.08s / 3.71s / 16.5% (DB 락 contention) |
| 피드 확장성 (90VU) | p95 / avg / 에러율 | **628ms / 106ms / 0%** |
| 통합 부하 (180VU, 10min) | http_req p95 / 처리량 | 2.96s / 71.2 req/s (46,789 요청) |
| **Redis kill 복원력** | 피드 + 로그인 | **전체 서비스 무중단** (CB + RedisUtil fallback) |
| Prometheus 경로 분포 | cache / inbox / pull | 59.5% / 36.7% / 3.7% |

> **8-7 추가 개선**: RedisUtil에 graceful fallback 적용 → Redis 다운 시에도 로그인 + 피드 모두 정상 동작
> (refresh 토큰만 미저장, access token TTL 내 서비스 이용 가능)

상세: `docs/optimization/21-phase8-resilience-hotkey.md`

---

## 셀링포인트 #2: 서비스 확장성 한계 측정 및 돌파

> 계획서: `plan/서비스_확장성_한계_측정_및_돌파.md`
> 태스크: `plan/task.md`

### 확장성 Phase 0: 측정 환경 준비 — ✅ 완료
- [x] 시드 데이터 확장 (팔로잉 수별 테스트 그룹 생성)
- [x] k6 스크립트: concurrency-test.js, scalability-test.js
- [x] 모니터링 설정 (Actuator + Caffeine recordStats + HikariCP 메트릭)

### 확장성 Phase 1: 현재 상태 측정 + 문제 증명 — ✅ 완료
- [x] **1-1. 베이스라인 재측정** (GCP e2-standard-2)
  - 피드 p95=8.39s, 에러율 9.67%, 처리량 27.9 req/s
  - UserDetails Caffeine 캐싱 + HikariCP 50 → avg 1.25s로 개선
- [x] **1-2. 동시성 문제 증명** — Deadlock 78%, Lost Update 91건
- [x] **1-3. 팔로잉 수별 피드 성능** (90 VUs, 6그룹)
  - median 46~157ms (양호), p95 5.49~9.67s (팔로잉 수↑ → p95↑)
- [x] **1-4. VU ramp 테스트** (50→500 VU)
  - avg 1.97s, median 148ms, 에러 0.19%, 64.1 req/s
- [x] **1-5. 종합 리포트** → `docs/optimization/16-scalability-measurement-summary.md`

### 확장성 Phase 2: Part A 해결 — 동시성 정합성 확보 — ✅ 완료
- [x] **2-1. DB 원자적 연산** — PostCountEvent + @Async + JPQL atomic UPDATE
- [x] **2-2. 100 VU 테스트** — 에러 0%, diff=0, Deadlock 0%
- [x] **2-3. 스케일 테스트** — 200VU diff=0 / 500VU 서버과부하 23%
- [x] **2-4/2-5. 락 비교·Redis 카운터** → SKIP (측정 결과 불필요)
- [x] **2-6. 문서화** → `docs/optimization/15-concurrency-atomic-result.md`

**Phase 2 성과:**
| 지표 | Before (JPA Dirty Checking) | After (DB 원자적 연산) |
|------|---------------------------|---------------------|
| Deadlock | 78% | **0%** |
| Lost Update | 91건 괴리 | **0건 (diff=0)** |
| Row Lock avg | N/A | **54ms** |
| 에러율 (200 VU) | N/A | **0%** |

### 확장성 Phase 3: Part B 해결 — 피드 확장성 개선 — ✅ 완료
- [x] **3-1/3-2. FORCE INDEX(PRIMARY) 역순 PK 스캔 + 재측정**
  - EXPLAIN ANALYZE: 5000명 기준 214ms → 0.12ms (1,783배 개선)
  - 2단계 쿼리 분리 구현 (Native SQL ID 조회 + QueryDSL 엔티티 페치)
  - 피드 p95: 7.24s → 2.2s (70%↓), 처리량: 64.1 → 111.8 req/s (74%↑)
- [x] **3-3~3-5. 캐시 분석 + SKIP** — HIT율 83-99%, Redis 캐시 이미 구현
- [x] **3-6. 문서화** → `docs/optimization/17-feed-query-optimization.md`

**Phase 3 성과:**
| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 피드 p95 (90 VU) | 7.24s | **2.2s** | 70% 감소 |
| VU ramp p95 (500 VU) | 16.35s | **2.75s** | 83% 감소 |
| 처리량 | 64.1 req/s | **111.8 req/s** | 74% 증가 |
| 에러율 (500 VU) | 0.19% | **0.00%** | 에러 제거 |
| p95 범위 (50→5000명) | 5.49~9.67s | 2.12~2.56s | 감도 대폭 감소 |

### 확장성 Phase 4: 통합 검증 + 결론 — ✅ 완료
- [x] **4-1. 통합 부하 테스트** (읽기 80% + 쓰기 20%, 50→500 VU)
  - p95=5.1s, avg=1.34s, 에러 0%, 96.2 req/s
  - 총 68,954 요청 (12분), 57,108건 전수 성공
- [x] **4-2. 최종 한계점** — 동시 500명 (≈ MAU 10,000명) 안정
- [x] **4-3. 최종 결과 문서화** → `docs/optimization/18-scalability-final-report.md`
- [x] **4-4. 포트폴리오 스토리** — 한 줄 요약 + 면접 질답 5개

---

## 셀링포인트 #3: 프라이버시 기반 접근 제어 아키텍처 — ✅ 완료

> 상세: `docs/optimization/19-privacy-access-control.md`

### 문제
- postId만 알면 CIRCLE/PERSONAL 글에 누구나 접근 가능
- Block 후 기존 좋아요/댓글/알림 미정리
- Follow 알림 동기 처리 (100~500ms 지연)
- Circle 해제 단방향만 처리
- unblock() 캐시 무효화 누락 버그

### 해결: 3중 방어 구조
- [x] **Phase 1:** PostVisibilityChecker — 모든 Post 개별 접근에 가시성 검증 (Block + Publicity)
- [x] **Phase 2:** 관계 변경 이벤트 시스템 — Follow 알림 비동기 전환 + 5개 이벤트 클래스
- [x] **Phase 3:** Block 상호작용 정리 — 양방향 좋아요/댓글/알림 비동기 정리 + 카운트 정합성
- [x] **Phase 4:** Circle 양방향 해제 — CacheManager 수동 evict
- [x] **Phase 5:** 컴파일 검증 통과

### 성과

| 항목 | 변경 |
|------|------|
| 보안 취약점 | 5개 프라이버시 갭 → **전부 해결** |
| Follow API | 동기 FCM → 비동기 (**-100~500ms**) |
| FollowCommandService 의존성 | 6개 → **4개** |
| 신규 파일 | 10개 (검증기 1 + 이벤트 5 + 리스너 4) |
| 수정 파일 | 15개 (도메인/인프라/서비스 전 계층) |

---

## GCP 테스트 환경

| 항목 | 값 |
|------|------|
| App VM | e2-standard-2 (2 vCPU, 8GB), 34.64.214.78 |
| Redis VM | e2-small, 10.0.1.20:6379 |
| Monitoring VM | e2-medium, 34.22.64.236 (k6+Grafana+InfluxDB) |
| MySQL | Docker on App VM, root/coredisc2024 |
| HikariCP | pool=50, min-idle=20 |
| 시드 데이터 | 10K members, 184K posts, 6.2M likes, 538K follows |
| 프로파일 | `--spring.profiles.active=local,cloud` |

---

## 환경 정보 (로컬, Phase 1~6용)

| 항목 | 값 |
|------|------|
| DB | MySQL (로컬), 데이터 918MB |
| 프로필 | `local` (Stub 패턴 적용) |
| 테스트 유저 | `loaduser_1` ~ `loaduser_10000` (비밀번호: `testpass123a`) |
| k6 VUs | 200명 로그인, 최대 100 VUs 동시 실행 |
| Redis | localhost:6379 |

---

## 참고 문서

- `context/OPTIMIZATION-CHECKLIST.md` — 전체 최적화 체크리스트 (상세 코드 포함)
- `context/OPTIMIZATION-POINTS.md` — 최적화 포인트 & 포트폴리오 전략
- `plan/서비스_확장성_한계_측정_및_돌파.md` — 확장성 마스터 계획서
- `plan/task.md` — 확장성 작업 체크리스트 + 진행 로그
- `docs/troubleshooting/` — 트러블슈팅 기록
- `docs/optimization/` — 최적화 작업 기록
  - `10-scalability-baseline.md` — GCP 베이스라인 측정
  - `11-concurrency-test.md` — 동시성 문제 증명 (Phase 1-2)
  - `15-concurrency-atomic-result.md` — 동시성 해결 결과 (Phase 2)
  - `16-scalability-measurement-summary.md` — Phase 1+2 종합 리포트
  - `17-feed-query-optimization.md` — 피드 쿼리 최적화 (Phase 3)
  - `18-scalability-final-report.md` — 최종 보고서 (Phase 4)
  - `20-hybrid-feed-architecture.md` — Phase 7: 하이브리드 피드
  - `21-phase8-resilience-hotkey.md` — Phase 8: 장애 복원력 + 핫 키 최적화
