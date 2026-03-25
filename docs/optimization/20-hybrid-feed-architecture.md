# Phase 7: Push/Pull 하이브리드 피드 아키텍처

> 작성일: 2026-03-17
> 환경: GCP e2-standard-2 (2 vCPU, 8GB), MySQL 8.0, Redis 7 (별도 VM)
> 데이터: 10,000 회원, 184,000 게시글, 410,000 팔로우

---

## 1. 배경 및 동기

Phase 1~6 최적화(역순 PK 스캔 + Redis DTO 캐시 + Thundering Herd 방어)로
피드 p95를 7.24s → 2.2s(90VU)로 개선했으나, **30초 TTL 만료 시 동시 유저가 DB를 때리는 구조적 한계**가 남아있었다.

일기 앱 특성상 **하루 1회 쓰기, 수십 회 읽기**이므로 Fan-out-on-Write가 최적.
글 발행 시 팔로워 인박스에 미리 push하면, 캐시 미스 시에도 효율적인 IN절 조회로 DB 부하를 줄일 수 있다.

---

## 2. 아키텍처 설계

### 2-1. Redis Sorted Set 기반 인박스

```
키: feed:inbox:{memberId}:{feedType}  (ALL | CORE)
score = postId (auto-increment PK → 시간순 보장)
member = postId (문자열)
```

**Sorted Set 선택 이유:**
- 커서 페이지네이션: `ZREVRANGEBYSCORE` O(log N)
- 게시글 삭제: `ZREM` O(log N)
- 중복 방지: 동일 score 자동 덮어쓰기
- 500개 cap: `ZREMRANGEBYRANK`로 관리

### 2-2. Write Path (Fan-out on Write)

```
publishPost() → DB save → PostPublishedEvent 발행
  ↓ (AFTER_COMMIT, @Async("fanoutExecutor"))
FeedFanoutEventListener:
  - PERSONAL → 작성자 본인 ALL 인박스에만 push
  - OFFICIAL → 전체 팔로워 ALL + circle 팔로워 CORE
  - CIRCLE   → circle 팔로워만 ALL + CORE
  - 팔로워 5000명 초과 시 skip (Celebrity threshold)
  - Redis Pipeline ZADD (1 network round-trip)
  - 팔로워 DTO 캐시 무효화 (새 글 즉시 반영)
```

### 2-3. Read Path (Inbox-first + DTO Cache + Pull Fallback)

```
findPostFeed()
  ├─ DTO 캐시 체크 (30초 TTL) → 히트 시 즉시 반환
  ├─ 인박스 조회 (Redis ZREVRANGE) → postIds
  │   → DB IN절 조회 (findPostSummariesByIds)
  │   → DTO 캐시 저장
  │   → 반환
  └─ 인박스 비어있으면 → Pull fallback (기존 역순 PK 스캔)
```

### 2-4. Edge Case 처리

| 이벤트 | 인박스 액션 |
|--------|-----------|
| 게시글 삭제 | 팔로워 인박스 ZREM |
| Block | 양방향 인박스에서 상대 글 제거 |
| Unfollow | 상대 글을 내 인박스에서 제거 |
| Follow | 상대의 최근 OFFICIAL 글 backfill |
| Circle 추가 | 상대의 CIRCLE+OFFICIAL 글 CORE 인박스 backfill |
| Circle 해제 | 상대의 CIRCLE 글 인박스에서 제거 |

---

## 3. 기술 선택 근거

### Spring @Async Event (vs RabbitMQ)

CoreDisc는 하루 최대 1만 건 발행. 초당 1건도 안 되는 처리량에 MQ를 도입하면
모니터링, DLQ, 연결 관리 등 운영 부담만 증가. 이미 5개 이벤트가 Spring @Async로
동작 중이므로 동일 패턴 사용. MAU 100만 이상 성장 시 리스너 내부만 Redis Queue로
교체 가능 (인터페이스 변경 없음).

### postId만 저장 (vs 전체 DTO)

- postId 1개 ≈ 64 bytes (ZSet 오버헤드 포함)
- 유저당 500개 × 2개 피드타입 = 64KB
- 10,000 유저 = 640MB (Redis 1.5GB 한도 내)
- 전체 DTO 저장 시 50배 → 32GB, 프로필 변경 시 전파 문제

---

## 4. 부하 테스트 결과

### 4-1. 테스트 환경

- **트래픽 가정**: 500 VU (동시접속), ~135 req/s 피크
- **MAU 환산**: ~40,000 MAU (일기 앱: 세션 3분, 하루 1-2회, 피크배수 3x)
- **일일 요청량**: ~1,166만 요청 (피크 8시간 기준)
- **테스트 패턴**: 50 → 100 → 200 → 500 VU 단계적 증가 (11분)

### 4-2. Ramp Test Before/After (500VU)

| 지표 | Before (Pull only) | After (Hybrid) | 개선 |
|------|-------------------|----------------|------|
| **feed p95** | 2.41s | **1.60s** | **33.6% 감소** |
| **feed p90** | 1.91s | **1.30s** | **31.9% 감소** |
| **feed avg** | 554ms | **387ms** | **30.1% 감소** |
| **feed med** | 26ms | 35ms | 유사 (캐시 히트) |
| **throughput** | 122 req/s | **135 req/s** | **10.7% 증가** |
| **total iterations** | 87,099 | **97,646** | **12.1% 증가** |
| **error rate** | 0.00% | 0.00% | 동일 |
| **p95 threshold** | FAIL (>2s) | **PASS (<2s)** | 달성 |

### 4-3. 팔로잉 수별 피드 성능 (90 VUs, 6그룹 × 15명 × 10회)

3단계 Before/After 비교: Phase 1-5(Pull only, 캐시 없음) → Phase 6(Pull + Redis 캐시) → **Phase 7(Hybrid)**

| 팔로잉 수 | Phase 1-5 p95 | Phase 6 p95 | **Phase 7 p95** | 총 개선 |
|-----------|:------------:|:-----------:|:--------------:|:-------:|
| 50 | 5.49s | 2.12s | **375ms** | **93.2%** |
| 200 | 5.55s | 2.0s | **386ms** | **93.0%** |
| 500 | 6.68s | 1.51s | **699ms** | **89.5%** |
| 1,000 | 7.03s | 2.2s | **540ms** | **92.3%** |
| 2,000 | 9.57s | 2.12s | **595ms** | **93.8%** |
| 5,000 | 9.67s | 2.56s | **504ms** | **94.8%** |
| **전체** | **7.24s** | **2.2s** | **522ms** | **92.8%** |

| 지표 | Phase 6 | Phase 7 | 개선 |
|------|---------|---------|------|
| avg | 312ms | **78ms** | **75.0% 감소** |
| median | 85ms | **15ms** | **82.4% 감소** |
| p95 | 2.2s | **522ms** | **76.3% 감소** |
| p95 범위 (50→5000) | 2.12~2.56s (0.44s) | **375~699ms (0.32s)** | 감도 27% 감소 |
| 처리량 | 33.4 req/s | **45.8 req/s** | **37.1% 증가** |

**핵심 발견**: 인박스 경로에서 캐시 미스 시 IN절 조회가 역순 PK 스캔보다 빠르고,
캐시 히트율이 높아져서 median 15ms(85ms→15ms)로 크게 개선.
팔로잉 5000명에서도 p95 504ms — **팔로잉 수에 무관한 균일 성능** 달성.

---

## 5. 실패와 학습 (디버깅 과정)

### 5-1. 첫 번째 시도: 인박스만, DTO 캐시 우회

**결과**: p95 2.41s → **7.56s** (3배 악화)

**원인**: 인박스 경로가 기존 30초 DTO 캐시를 완전히 우회.
모든 요청이 Redis inbox → DB 3쿼리(post JOIN, answers, TodayQuestion)를 타면서,
기존 캐시 히트율 ~95%가 0%로 떨어짐.

**교훈**: Fan-out은 "무엇을 캐시에 넣을지"를 바꾸는 것이지,
"캐시를 없애는 것"이 아니다. 인박스는 캐시의 대체가 아니라 보완이다.

### 5-2. 수정: 인박스 + DTO 캐시 조합

```java
// 1. DTO 캐시 먼저 체크 → 히트 시 즉시 반환
// 2. 미스 시 인박스 → DB → DTO 캐시 저장
// 3. 인박스도 비어있으면 Pull fallback
```

**결과**: p95 **1.60s** (33.6% 개선, threshold 달성)

---

## 6. 구현 파일

### 신규 생성 (8개)

| 파일 | 역할 |
|------|------|
| `PostPublishedEvent.java` | 게시글 발행 이벤트 |
| `PostDeletedEvent.java` | 게시글 삭제 이벤트 |
| `FeedFanoutEventListener.java` | Fan-out (Push) + 팔로워 캐시 무효화 |
| `FeedCleanupEventListener.java` | 삭제/Block/Unfollow/Follow/Circle 인박스 정리 |
| `FeedInboxService.java` | Redis Sorted Set CRUD + Pipeline |
| `FeedReadService.java` | 인박스 우선 읽기 + DTO 캐시 + Pull fallback |
| `FeedInboxBackfillService.java` | Follow/Circle/Block 시 backfill/cleanup |
| `FeedInboxWarmupRunner.java` | 앱 시작 시 기존 게시글 인박스 backfill |

### 수정 (8개)

| 파일 | 변경 |
|------|------|
| `PostCommandServiceImpl.java` | publishPost/deletePost에 이벤트 발행 |
| `PostQueryServiceImpl.java` | findPostFeed → FeedReadService 위임 |
| `AsyncConfig.java` | fanoutExecutor 스레드풀 추가 |
| `QueryFollowRepository.java` | findFollowerIds/findCircleFollowerIds 추가 |
| `QueryFollowRepositoryImpl.java` | 위 메서드 구현 |
| `QueryPostRepository.java` + `Impl` | findPostSummariesByIds/findRecentPostIdsByMemberId |
| `PostRepository.java` + Adaptor | 도메인 인터페이스 연결 |

---

## 7. 면접 대비 핵심 포인트

1. **왜 Sorted Set?** — 피드는 역순 정렬 + 커서 페이지네이션 + 삭제가 필수. 세 연산 모두 O(log N).
2. **왜 Spring @Async?** — 하루 1만 건에 MQ는 과설계. 리스너 교체만으로 MQ 전환 가능.
3. **왜 postId만?** — 메모리 50배 절약 + 데이터 정합성 문제 제거. IN절 조회 < 5ms.
4. **실패에서 배운 것** — 인박스는 캐시의 대체가 아니라 "캐시 미스 시 DB 부하를 줄이는 보완". DTO 캐시와 조합해야 효과적.
