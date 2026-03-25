# CoreDisc 백엔드 포트폴리오 — 딥다이브

> **프로젝트**: CoreDisc (자기탐색 저널링 SNS)
> **역할**: 백엔드 개발 (5인 팀)
> **기간**: 2026.01 ~ 2026.03
> **기술스택**: Java 17, Spring Boot 3.5, JPA/QueryDSL, MySQL 8.0, Redis, AWS S3, FCM, Terraform, Prometheus/Grafana, k6

---

# Selling Point 1. Push/Pull 하이브리드 피드 아키텍처

## 1. 왜 필요했는가

CoreDisc는 사용자가 매일 4개의 질문에 답변하고, 팔로워의 피드에서 서로의 답변을 읽는 저널링 SNS다. 피드 조회는 전체 트래픽의 약 40%를 차지하는 핵심 API였다.

초기에는 **Pull-only 아키텍처**로 구현했다. 요청이 올 때마다 팔로우 관계를 조회하고, 해당 사용자들의 게시글을 역순 정렬하여 반환하는 방식이다. 여기에 Redis DTO 캐시(TTL 30초)를 얹어 반복 요청을 흡수했다.

문제는 **캐시 TTL 만료 시점**에 발생했다. 30초마다 캐시가 만료되면, 그 순간에 접속 중인 모든 사용자의 요청이 동시에 DB를 조회한다. 이를 **Thundering Herd**라고 한다. 90명의 동시 접속자(VU) 기준으로 피드 p95 레이턴시가 2.2초까지 치솟았고, 팔로워 5000명인 사용자의 피드는 2.56초에 달했다.

SETNX 기반 분산 락으로 Thundering Herd를 일부 완화했지만, 락을 획득하지 못한 요청들이 50ms 간격으로 최대 10회 폴링하는 구조여서 근본적인 해결책은 아니었다.

## 2. 핵심 발견: "일기 앱은 쓰기보다 읽기가 압도적으로 많다"

CoreDisc의 트래픽 특성을 분석했다. 사용자는 **하루에 1번 글을 쓰고, 수십 번 피드를 본다**. 즉 쓰기:읽기 비율이 대략 1:50이다. 이런 특성에서는 쓰기 시점에 한 번 비용을 지불하고, 읽기를 극도로 가볍게 만드는 **Fan-out-on-Write** 전략이 최적이다.

다만, 팔로워가 극단적으로 많은 셀럽 계정(5000명 이상)에 Fan-out을 적용하면 쓰기 비용이 폭발한다. 트위터가 겪었던 유명한 문제다. 그래서 **일반 사용자는 Push, 셀럽은 Pull**이라는 하이브리드 전략을 선택했다.

## 3. 아키텍처 설계

### 3-1. Redis Sorted Set 기반 인박스

각 사용자마다 Redis에 피드 인박스를 둔다.

```
키: feed:inbox:{memberId}:{feedType}   (ALL | CORE)
Score: postId (auto-increment PK → 시간순 보장)
Member: postId (문자열)
```

**Sorted Set을 선택한 이유:**
- `ZREVRANGEBYSCORE`로 커서 기반 페이지네이션이 O(log N)에 가능
- `ZREM`으로 특정 게시글 삭제가 O(log N)
- 동일 postId를 다시 넣어도 자동 중복 방지
- `ZREMRANGEBYRANK`로 인박스 크기를 500개로 일정하게 유지

Hash나 List 대신 Sorted Set을 선택한 핵심 이유는 **커서 페이지네이션과 개별 삭제를 모두 O(log N)에 처리**할 수 있기 때문이다.

인박스에는 postId만 저장한다. 게시글 DTO 전체를 저장하면 게시글이 수정될 때 모든 팔로워의 인박스를 갱신해야 하고, 인박스 하나당 수십 KB가 되어 Redis 메모리가 폭발한다(10K 유저 × 500 DTO ≈ 32GB vs postId만 저장 시 ≈ 64MB).

### 3-2. Write Path — Fan-out on Write

게시글이 발행되면 Spring Event를 통해 비동기로 팔로워 인박스에 push한다.

```
publishPost()
  → DB 저장 → PostPublishedEvent 발행
  → @TransactionalEventListener(AFTER_COMMIT)
  → @Async("fanoutExecutor")
  → FeedFanoutEventListener:
      - PERSONAL → 작성자 본인 인박스에만 push
      - OFFICIAL → 전체 팔로워 ALL + 서클 팔로워 CORE 인박스
      - CIRCLE   → 서클 팔로워만 ALL + CORE 인박스
      - 팔로워 5000명 초과 → fan-out 생략 (Pull로 처리)
      - Redis Pipeline ZADD (전체 팔로워를 1 네트워크 왕복으로 처리)
      - 팔로워 DTO 캐시 무효화 (새 글 즉시 반영)
```

`@TransactionalEventListener(AFTER_COMMIT)`을 사용한 이유가 있다. 트랜잭션 커밋 전에 fan-out하면, 게시글이 아직 DB에 없는 상태에서 팔로워가 피드를 조회할 수 있다. 반드시 **커밋 이후에** fan-out해야 데이터 정합성이 보장된다.

### 3-3. Read Path — 3계층 탐색

피드 조회 시 3단계로 탐색한다.

```
findPostFeed()
  ├─ 1단계: DTO 캐시 체크 (Redis, TTL 30초) → HIT 시 즉시 반환
  ├─ 2단계: 인박스 조회 (Redis ZREVRANGE) → postId 목록 획득
  │   → DB에서 IN절로 게시글 조회 → DTO 캐시 저장 → 반환
  └─ 3단계: 인박스 비어있으면 → Pull fallback (기존 역순 PK 스캔)
```

1단계 DTO 캐시는 동일 요청의 반복을 흡수한다. 2단계 인박스가 있으면 `WHERE post.id IN (?, ?, ..., ?)` 쿼리 한 번으로 게시글을 가져온다. 3단계 Pull fallback은 인박스가 비어있거나 셀럽 계정처럼 fan-out이 생략된 경우의 안전망이다.

### 3-4. 실패에서 배운 점 — "인박스만으로는 안 된다"

처음에는 DTO 캐시를 제거하고 인박스만으로 피드를 제공하려 했다. 논리적으로는 맞아 보였다. 인박스에 항상 최신 데이터가 있으니 캐시가 필요 없다고 생각했다.

**결과는 참담했다. p95가 7.56초로, 최적화 전보다 3배 느려졌다.**

원인을 분석하니, DTO 캐시의 HIT율이 95%에 달했다. 100번의 요청 중 95번은 캐시에서 즉시 반환되고, 5번만 Redis 인박스 + DB 조회를 탔던 것이다. 인박스만 쓰면 100번 모두 Redis + DB를 거쳐야 했다.

이 실패에서 배운 교훈: **DTO 캐시와 인박스는 대체 관계가 아니라 보완 관계다.** DTO 캐시는 단기 반복 요청을 흡수하고, 인박스는 캐시 미스 시 DB 부하를 최소화한다. 두 계층이 함께 있어야 비로소 성능이 나온다.

## 4. Edge Case 처리

SNS에서는 관계가 실시간으로 변한다. 차단, 언팔로우, 서클 변경 시 인박스에 잔존하는 게시글이 노출되면 치명적이다.

| 이벤트 | 인박스 액션 | 이유 |
|--------|-----------|------|
| 게시글 삭제 | 팔로워 인박스에서 ZREM | 삭제된 글이 피드에 노출 방지 |
| Block | 양방향 인박스에서 상대 글 제거 | 차단한/된 사용자 글 비노출 |
| Unfollow | 상대 글을 내 인박스에서 제거 | 더 이상 팔로잉하지 않는 사용자 |
| Follow | 상대의 최근 OFFICIAL 글 backfill | 팔로우 직후 빈 피드 방지 |
| Circle 추가 | 상대의 CIRCLE+OFFICIAL 글 CORE 인박스 backfill | 서클 전환 즉시 반영 |
| Circle 해제 | 상대의 CIRCLE 글 인박스에서 제거 | 서클 해제 후 비공개 글 비노출 |

특히 **Block과 Unfollow는 캐시도 동기적으로 즉시 무효화**한다. 비동기로 처리하면 TTL(30초) 동안 차단한 사용자의 글이 여전히 보이는 문제가 있었다. 응답 시간에 1~5ms만 추가되므로 동기 처리로 결정했다.

## 5. 장애 복원력 — Redis Circuit Breaker

피드의 핵심 인프라인 Redis가 죽으면 어떻게 되는가? 인박스도, DTO 캐시도 모두 Redis에 있다.

이 문제를 해결하기 위해 **수동 Circuit Breaker**를 구현했다.

```
상태 전이: CLOSED → OPEN (연속 5회 실패) → HALF_OPEN (10초 후) → CLOSED (성공 시)

CLOSED 상태:
  - 정상 운영, Redis 호출 실행
  - 실패 시 consecutiveFailures 카운트 증가
  - 5회 연속 실패 → OPEN으로 전이

OPEN 상태:
  - Redis 호출 즉시 스킵 → Pull fallback으로 직접 DB 조회
  - 10초 경과 후 → HALF_OPEN

HALF_OPEN 상태:
  - 1건만 Redis 시도
  - 성공 → CLOSED (복구 확인)
  - 실패 → 다시 OPEN (아직 장애 중)
```

**Resilience4j를 사용하지 않은 이유**: 프로젝트에서 Circuit Breaker를 사용하는 지점이 Redis와 FCM 딱 2곳이다. Resilience4j를 도입하면 라이브러리 의존성, 설정 파일, 학습 비용이 추가되는데, 70줄의 수동 구현으로 동일한 기능을 구현할 수 있었다. 코드가 명시적이어서 디버깅도 쉽다. 모니터링은 Micrometer gauge(`redis.circuit.state`)로 Grafana에서 실시간 확인한다.

### Redis kill 테스트 결과

GCP 환경에서 실제로 Redis 프로세스를 kill한 뒤 피드를 조회했다.

- Redis 장애 중에도 **피드 HTTP 200 반환** (Pull fallback 정상 동작)
- **신규 로그인 성공** (JWT 인증 레이어의 Redis 장애 내성 별도 구현)
- Redis 재시작 후 **자동 복구** (HALF_OPEN → CLOSED)

## 6. 성능 결과

### Feed-by-following 테스트 (90 VU, 팔로워 50~5000명)

| 팔로워 수 | Before (Pull-only) p95 | After (Hybrid) p95 | 개선율 |
|-----------|----------------------|-------------------|--------|
| 50 | 2.12s | **375ms** | 82.3% |
| 200 | 2.00s | **386ms** | 80.7% |
| 500 | 1.51s | **699ms** | 53.6% |
| 1,000 | 2.20s | **540ms** | 75.5% |
| 2,000 | 2.12s | **595ms** | 71.9% |
| 5,000 | 2.56s | **504ms** | 80.3% |

**핵심 성과**: 팔로워 50명이든 5000명이든 **375~699ms로 균일한 성능**. Pull-only 방식에서는 팔로워 수에 비례해 성능이 저하되었지만, 하이브리드 방식에서는 인박스 크기가 동일(500개 cap)하므로 팔로워 수와 무관하게 일정하다.

### Prometheus 경로 분포

실제 운영 패턴에서의 경로 분포:
- **cache**: 59.5% (DTO 캐시 HIT)
- **inbox**: 36.7% (인박스 → IN절 조회)
- **pull**: 3.7% (fallback DB 조회)

전체 요청의 96.2%가 DB를 직접 조회하지 않는다.

---

# Selling Point 2. 이벤트 기반 비동기 아키텍처

## 1. 문제 인식

좋아요 API의 처리 흐름을 추적했을 때, 비즈니스 로직(좋아요 생성)은 10ms면 끝나지만 **알림 관련 로직이 250ms 이상**을 차지했다.

```
Before (동기 처리):
[API 요청] → [좋아요 생성 10ms] → [알림 저장 DB 5ms] → [디바이스 조회 DB 5ms]
           → [FCM 토큰 검증 200ms] → [FCM 전송 200ms] → [응답]
           = 총 420ms+ (FCM이 응답 시간의 95%를 차지)
```

문제는 FCM 호출뿐만이 아니었다. `PostLikeCommandServiceImpl`이 5개의 의존성(PostLikeRepository, DeviceRepository, NotificationService, FcmService, EventPublisher)을 갖고 있었다. 좋아요를 생성하는 서비스가 알림 발송, 디바이스 관리까지 알아야 했다. 이는 **단일 책임 원칙 위반**이자 테스트를 어렵게 만드는 근본 원인이었다.

더 심각한 문제는 **FCM 장애 전파**다. Firebase 서버에 문제가 생기면 FCM 호출이 타임아웃될 때까지 API 스레드가 블로킹되고, 이것이 Tomcat 스레드풀 고갈로 이어져 좋아요뿐 아니라 **모든 API가 영향**을 받는다.

## 2. 설계 결정: Spring Event + @TransactionalEventListener

### 왜 Kafka/RabbitMQ가 아닌 Spring Event인가

CoreDisc의 일일 게시글 발행량은 최대 1만 건이다. 초당 1건도 안 되는 처리량에 메시지 큐를 도입하면:
- 브로커 인스턴스 운영/모니터링 비용
- Dead Letter Queue 관리
- 연결 풀 관리
- 메시지 직렬화/역직렬화 오버헤드

이 모든 운영 부담이 추가된다. 반면 Spring Event는 JVM 내부에서 메서드 호출로 동작하므로 인프라 비용이 제로다. 추후 MAU 100만 이상으로 성장하면 리스너 내부만 Redis Queue 호출로 교체하면 되기 때문에, 현재 규모에서는 Spring Event가 최선의 선택이다.

### @TransactionalEventListener(AFTER_COMMIT)의 의미

일반 `@EventListener`와의 결정적 차이가 있다.

```java
// ❌ @EventListener — 트랜잭션 커밋 전에 실행
// 알림을 보냈는데 좋아요 트랜잭션이 롤백되면?
// → 좋아요는 안 됐는데 알림은 간 상태

// ✅ @TransactionalEventListener(AFTER_COMMIT)
// 트랜잭션이 성공적으로 커밋된 후에만 실행
// → 좋아요가 확정된 후 알림 발송 → 데이터 정합성 보장
```

이벤트 객체에는 **엔티티가 아닌 ID만 전달**한다. `@TransactionalEventListener`는 원래 트랜잭션이 커밋된 후 별도 컨텍스트에서 실행되기 때문에, 원래 트랜잭션에서 로딩한 엔티티의 Lazy Loading이 동작하지 않는다. ID를 전달하고 리스너에서 다시 조회하는 것이 안전하다.

## 3. 비동기 처리의 핵심: 이벤트 흐름

```
[좋아요 API 요청]
  → PostLikeCommandServiceImpl.createLike()
      → DB: 좋아요 생성 + Post likeCount 증감 이벤트 발행
      → eventPublisher.publishEvent(NotificationEvent.like(...))
  → [응답 반환] (10ms 내)

  [트랜잭션 커밋 후, notificationExecutor 스레드에서]
  → NotificationEventListener.handleNotificationEvent()
      → DB: 알림 저장
      → FCM Circuit Breaker 체크
        → OPEN: skipCounter 증가, FCM 호출 생략
        → AVAILABLE: sendWithRetry()
          → 성공: successCounter 증가
          → 실패: 1회 재시도 (100ms backoff) → 영구 실패 시 failureCounter 증가
```

리팩토링 전후 의존성 변화:
- `PostLikeCommandServiceImpl`: 5개 → 3개 (DeviceRepo, NotificationService, FcmService 제거)
- `CommentCommandServiceImpl`: 6개 → 4개 (동일 의존성 제거)
- 알림 발송 코드: 30줄+ → `eventPublisher.publishEvent()` 1줄

## 4. 5개 전용 스레드풀 — 장애 격리 전략

모든 비동기 작업을 하나의 스레드풀에서 처리하면, FCM 장애로 알림 스레드가 모두 블로킹될 때 피드 fan-out, 카운터 업데이트 등 다른 비동기 작업까지 멈춘다. 이를 방지하기 위해 **용도별 전용 스레드풀 5개를 분리**했다.

| Executor | core/max | 큐 | 역할 | Rejection Policy |
|----------|----------|------|------|------------------|
| mailExecutor | 2/5 | 10 | 이메일 발송 | 기본(AbortPolicy) |
| countExecutor | 4/16 | 200 | 좋아요/댓글 카운트 원자적 증감 | CallerRunsPolicy |
| batchExecutor | 4/8 | 20 | 일일/월간 배치 통계 | 기본 |
| fanoutExecutor | 4/8 | 100 | 피드 인박스 fan-out | CallerRunsPolicy |
| notificationExecutor | 4/10 | 500 | 알림 저장 + FCM 발송 | CallerRunsPolicy |

**CallerRunsPolicy의 의미**: 큐가 가득 차면 작업을 거부(AbortPolicy, HTTP 500)하는 대신, 호출 스레드가 직접 실행한다. 처리 속도는 떨어지지만 **단 한 건의 작업도 유실되지 않는다**. AbortPolicy에서 CallerRunsPolicy로 바꾸기만 해도 HTTP 500이 0건이 되었다.

각 Executor는 `ExecutorServiceMetrics.monitor(meterRegistry, ...)`로 Micrometer에 등록되어, Grafana에서 활성 스레드 수, 큐 깊이, 완료 작업 수를 실시간 모니터링한다.

### PostCountEvent의 트랜잭션 분리

좋아요/댓글 카운트 증감은 `countExecutor`에서 **별도 트랜잭션(REQUIRES_NEW)**으로 처리한다. 이유는 FK 데드락 방지다.

```
좋아요 트랜잭션: INSERT post_like (FK → post)  →  UPDATE post SET like_count = like_count + 1
댓글 트랜잭션:   INSERT comment (FK → post)     →  UPDATE post SET comment_count = comment_count + 1

두 트랜잭션이 동시에 같은 post 행을 잠그면 데드락 발생
→ 카운트 증감을 별도 트랜잭션으로 분리하여 원래 트랜잭션과 격리
```

## 5. 알림 파이프라인 전체 복원력

### FCM Circuit Breaker

Redis Circuit Breaker와 동일한 패턴이지만, FCM은 복구가 느리므로 OPEN_DURATION을 30초(Redis는 10초)로 설정했다.

중요한 차이점: **영구 오류와 일시 오류를 구분**한다. `INVALID_ARGUMENT`, `UNREGISTERED` 같은 토큰 문제는 FCM 서버 장애가 아니라 클라이언트 토큰 문제이므로 실패 카운트에 포함하지 않는다. 이렇게 하지 않으면 유효하지 않은 토큰이 쌓여서 정상 상황에서도 Circuit이 Open되는 오탐이 발생한다.

### 이중 토큰 검증 제거

기존 코드에서 `isTokenValid()`가 실제로 `FirebaseMessaging.send()`를 호출하여 토큰을 검증했다. 그 뒤에 `sendNotificationToToken()`에서 또 한 번 전송한다. **기기당 FCM 2회 호출(400ms)**.

`isTokenValid()`를 단순 null/blank 체크로 변경하여 **기기당 1회 호출(200ms)**로 줄였다. 이것만으로 알림 처리량이 25 → 50 notifs/sec으로 2배 향상됐다.

### 스케줄러 비동기화

5분 주기 알림 리마인더 스케줄러가 대상 사용자 1000명 × 2기기 × 400ms = **13분간 스케줄러 스레드를 블로킹**하고 있었다. 5분 주기인데 13분 걸리니 다음 실행과 겹치는 심각한 문제.

스케줄러는 **DB 쿼리(대상자 선별)만 수행**하고, FCM 호출은 `notificationExecutor.execute()`로 위임했다. 스케줄러 스레드는 수초 내 반환되고, 2000건의 FCM 호출은 10개 워커가 병렬로 ~40초에 처리한다.

## 6. Observability — 알림 파이프라인 메트릭

운영 환경에서 "알림이 잘 가고 있는가?"를 답하기 위해 6종의 Micrometer 메트릭을 등록했다.

| 메트릭 | 타입 | 의미 |
|--------|------|------|
| `notification.send.duration` | Timer | 알림 처리 전체 시간 (DB 저장 + FCM 발송) |
| `notification.send.success` | Counter | FCM 성공 건수 |
| `notification.send.failure` | Counter | 영구 실패 건수 |
| `notification.send.retry` | Counter | 재시도 횟수 |
| `notification.fcm.skip` | Counter | CB OPEN으로 스킵된 건수 |
| `fcm.circuit.state` | Gauge | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |

Grafana에서 `skip` 카운터가 급증하면 FCM 장애를 의미하고, `retry` 대비 `failure` 비율이 높으면 재시도 전략 재검토가 필요하다. `circuit.state` 타임라인으로 CB 동작 이력을 추적한다.

## 7. 성능 결과

### Story 1 — 코드 최적화 (동일 인프라, e2-standard-2)

| 지표 | Before | After | 변화 |
|------|--------|-------|------|
| http_req p95 | 3.83s | **3.06s** | **-20.1%** |
| Like p95 | 4.49s | **3.64s** | -18.9% |
| Follow p95 | 906ms | **707ms** | -22.0% |
| 처리량 | 48.33 req/s | **57.72 req/s** | **+19.4%** |
| HTTP 500 | 0 | **0** | CallerRunsPolicy 보호 |

### Story 2 — 인프라 분리 (동일 코드, e2-standard-4 + Redis 분리)

| 지표 | Before (2vCPU) | After (4vCPU + Redis VM) | 변화 |
|------|----------------|--------------------------|------|
| Notification p95 | 3.64s | **421ms** | **-88.4%** |
| Like p95 | 3.71s | **489ms** | -86.8% |
| Feed p95 | 3.07s | **237ms** | -92.3% |
| 처리량 | 57.7 req/s | **274 req/s** | **+375%** |

---

# Selling Point 3. 측정 기반 9단계 성능 최적화 여정

## 1. 철학: "추측하지 말고 측정하라"

성능 최적화에서 가장 위험한 것은 **"여기가 느릴 것 같다"는 추측**이다. 추측에 기반한 최적화는 실제 병목이 아닌 곳에 시간을 낭비하게 만든다.

CoreDisc 최적화는 **매 단계마다 측정 → 분석 → 해결 → 검증** 사이클을 엄격하게 따랐다.

```
[측정] p6spy로 쿼리 수 측정, EXPLAIN으로 실행 계획 분석, k6로 부하 테스트
  ↓
[분석] 병목 지점 특정 (어느 API가, 왜, 얼마나 느린지)
  ↓
[해결] 가장 임팩트 큰 병목부터 해결
  ↓
[검증] 동일 조건에서 Before/After 부하 테스트
  ↓
[다음 단계] 새로운 병목 특정 → 반복
```

## 2. 측정 환경 구축 (Phase 0)

### 시드 데이터의 중요성

빈 데이터베이스에서는 모든 API가 빠르다. 문제는 **실제 규모의 데이터가 쌓인 후**에 발생한다.

Python 스크립트로 **918MB 시드 데이터**를 생성했다:
- 10,000명의 사용자
- 200,000건의 게시글
- 800,000건의 답변
- 410,000건의 팔로우 관계
- 1,000,000건의 알림

이 대량 데이터에서 부하 테스트를 돌리자, 개발 환경에서는 보이지 않던 버그가 터졌다. TodayQuestion 테이블에 UNIQUE 제약 조건이 없어서 중복 데이터가 쌓였고, `findBy`가 `getSingleResult()`를 호출하면서 **NonUniqueResultException으로 에러율이 44%**까지 치솟았다. `findBy` → `findFirstBy`(LIMIT 1)로 변경하고, `.get()` → `.ifPresent()`로 안전 처리하여 해결했다.

**교훈: 대량 데이터 테스트는 버그를 발견하는 가장 효과적인 방법이다.**

### Baseline 측정

918MB 데이터, 100 VU, 3분 30초 부하 테스트 결과:

| 지표 | 값 |
|------|------|
| http_req p95 | **518ms** |
| Feed p95 | 489ms |
| Profile p95 | 438ms |
| 처리량 | **86 req/s** |
| 에러율 | 0.01% |

이 수치가 모든 최적화의 기준점이 된다.

## 3. Phase 1 — 인덱스 + 트랜잭션 (기초 체력)

EXPLAIN 분석 결과, 5개 테이블에서 비효율적인 쿼리 실행 계획이 발견됐다.

**가장 심각했던 TodayQuestion:**
```sql
-- Before: rows=212, filtered=1.11%
-- 212행을 스캔해서 1행만 반환 (99%의 행을 불필요하게 읽음)
SELECT * FROM today_question
WHERE member_id = ? AND question_order = ? AND selected_date BETWEEN ? AND ?

-- After: 복합 인덱스 (member_id, question_order, selected_date) 추가
-- rows=1, filtered=100%
-- 인덱스로 정확히 1행만 접근
```

7개 복합 인덱스를 추가하고, QueryService에 `@Transactional(readOnly=true)`를 적용하고, HikariCP 커넥션 풀을 튜닝(max:20, timeout:5s)했다.

**결과: p95 518ms → 266ms (-48.6%)**. 인덱스만으로 거의 절반이 줄었다. "인덱스가 성능의 기초 체력"이라는 것을 체감한 순간이었다.

## 4. Phase 2~4 — N+1, 비동기, 캐싱

### Phase 2: N+1 제거

피드 10건 조회 시 16~17개 쿼리가 실행되고 있었다. 원인은 각 게시글마다 TodayQuestion을 개별 조회하는 N+1 패턴이었다.

배치 쿼리 + HashMap 룩업 패턴으로 해결했다:
1. 게시글 목록에서 memberIds, questionOrders 추출
2. `findByMemberIdInAndQuestionOrderInAndSelectedDateBetween()` 1개 쿼리로 전체 질문 조회
3. `Map<"memberId_questionOrder", TodayQuestion>` 룩업 맵 구성
4. 메모리에서 O(1) 매핑

댓글도 마찬가지로, 각 댓글의 답글 수를 개별 조회하던 것을 `GROUP BY parent_id` 배치 집계 쿼리로 변경했다.

**결과: 피드 16→7 쿼리 (-56%), 댓글 ~30+→4 쿼리 (-87%)**

### Phase 3: 비동기 이벤트 전환

좋아요/댓글 알림을 동기에서 비동기로 전환(SP2에서 상세 설명).

**결과: http_req p95 317ms → 270ms (-14.8%)**

### Phase 4: 멀티 레이어 캐싱

Caffeine 로컬 캐시로 팔로잉/서클 ID를 캐싱. 피드 쿼리에서 follow 테이블 서브쿼리를 IN절로 대체.

**결과: 캐시 HIT 시 follow 테이블 접근 0회**

## 5. Phase 5~6 — 배치 + 최종 검증

### 배치 최적화

- 임시 게시글 정리: 전체 로딩(OOM 위험) → 100건 청크 기반
- 4개 통계 배치: 순차 → `CompletableFuture.allOf()` 병렬 실행 (~75% 시간 단축)
- 리마인더 스케줄러: 멤버당 4~8 쿼리 → 전체 2개 배치 쿼리 (99.7% 감소)

### Phase 6 로컬 최종 결과 (Phase 1~5 누적)

| 지표 | Baseline | Phase 6 | 총 개선율 |
|------|----------|---------|----------|
| Feed p95 | 489ms | **246ms** | **-49.7%** |
| Profile p95 | 438ms | **127ms** | **-71.0%** |
| Notification p95 | 439ms | **167ms** | **-61.9%** |
| http_req p95 | 518ms | **296ms** | **-42.9%** |
| 처리량 | 86 req/s | **94 req/s** | +9.3% |

## 6. Phase 7~9 — 하이브리드 피드, 장애 복원력

Phase 7(하이브리드 피드)과 Phase 8~9(Circuit Breaker, 알림 복원력)는 SP1과 SP2에서 상세히 다뤘다.

### GCP 최종 통합 테스트 결과 (180 VU, 10분)

| 지표 | Phase 8 (2vCPU) | Phase 9 (4vCPU + Redis 분리) | 변화 |
|------|-----------------|------------------------------|------|
| http_req p95 | 2.96s | **226ms** | **-92.4%** |
| Feed p95 | 3.07s | **237ms** | -92.3% |
| 처리량 | 71.2 req/s | **174 req/s** | +144.4% |
| 에러율 | 6.53% | **2.98%** | -54.4% |

## 7. 가장 큰 교훈: "병목이 코드가 아닐 수 있다"

Phase 9에서 **동일한 코드**를 두 가지 인프라에서 돌려 봤다.

| 환경 | p95 | 처리량 |
|------|-----|--------|
| e2-standard-2 (2vCPU, 통합 Redis) | 3.06s | 58 req/s |
| e2-standard-4 (4vCPU, Redis 분리) | **226ms** | **174 req/s** |

코드는 한 줄도 바꾸지 않았는데, 인프라만 바꾸니 **p95가 92% 감소하고 처리량이 3배**가 됐다. 코드 최적화(Phase 1~8)의 누적 효과가 20% 개선이었던 것에 비해, 인프라 변경 한 번이 86% 개선을 가져왔다.

이것은 코드 최적화가 무의미하다는 뜻이 아니다. 코드 최적화가 없었다면 인프라를 늘려도 비효율적인 쿼리가 CPU를 낭비했을 것이다. **코드 최적화는 인프라 효율을 극대화하는 전제 조건**이다.

이 경험에서 배운 것: **성능 문제의 원인을 추측하지 말고 측정으로 증명하라.** "코드가 느린 것 같다" → 인프라가 병목이었다. "인프라를 늘리면 된다" → 코드가 비효율적이면 인프라를 늘려도 한계가 있다. 둘 다 측정 없이는 알 수 없는 사실이다.

## 8. 수용 능력 분석

Phase 9 최종 인프라(e2-standard-4 + Redis 분리 VM) 기준:

| 구간 | DAU | MAU | 조건 |
|------|-----|-----|------|
| 쾌적 | 10,000~15,000 | 50,000~75,000 | p95 < 500ms |
| 지속 가능 | ~30,000 | ~150,000 | p95 < 1s |

다음 스케일 경로:
1. MySQL VM 분리 → DAU 20K~30K
2. App 수평 확장(2대) → DAU 40K~60K
3. Redis Cluster + DB Read Replica → DAU 100K+

## 9. 기술 의사결정 요약

| 결정 | 선택 | 대안 | 선택 이유 |
|------|------|------|----------|
| 로컬 캐시 | Caffeine | Redis | 단일 서버, 네트워크 0ms |
| 메시지 전달 | Spring Event | Kafka/RabbitMQ | 일일 1만 건, 인프라 비용 제로 |
| 스케줄링 | @Scheduled | Quartz | 단일 서버, 클러스터 락 불필요 |
| Circuit Breaker | 수동 구현 | Resilience4j | 2곳만 사용, 70줄 코드, 디버깅 용이 |
| 동시성 제어 | DB Atomic UPDATE | Redis 카운터 | 200 VU에서 0% Lost Update |
| 배치 프레임워크 | 직접 구현 | Spring Batch | 5~10분 작업에 메타데이터 9테이블 과잉 |
| 피드 아키텍처 | Push/Pull Hybrid | Fan-out only | 셀럽 계정 fan-out 비용 방지 |
| 인박스 저장 | postId only | DTO 전체 | 메모리 500배 절감 (64MB vs 32GB) |

---

# 회고

## 잘한 점
- 매 Phase마다 **Before/After 수치**를 기록하여, 각 최적화의 효과를 정확히 파악
- 실패 경험(Inbox-only p95 7.56s)을 숨기지 않고 기록하여, **왜 현재 설계가 최선인지** 설명 가능
- **ADR(Architecture Decision Record)**로 모든 기술 선택의 근거를 문서화

## 아쉬운 점
- Phase 0에서 Grafana/Prometheus를 먼저 구축했다면, 중간 Phase에서 실시간 모니터링이 가능했을 것
- TodayQuestion UNIQUE 제약 조건을 초기에 설정했다면 에러율 44% 사태를 예방할 수 있었을 것
- 이미지 CDN + WebP 변환까지 진행하지 못한 점

## 성장한 점
- **"추측 vs 측정"**: 감으로 최적화하던 습관에서, 데이터로 의사결정하는 습관으로 전환
- **장애 대응 설계**: "잘 되는 경우"만 고려하던 사고에서, "장애가 나면 어떻게 되는가?"를 먼저 묻는 사고로 전환
- **트레이드오프 사고**: 모든 기술 선택에는 대가가 있다는 것을 체득. "이 기술이 좋다"가 아니라 "이 상황에서 이 기술이 적합하다"로 판단
