# 11. 동시성 정합성 테스트 (Phase 1-2)

> 날짜: 2026-03-13
> 관련 코드: `PostLikeCommandServiceImpl.createLike()`, `Post.likeCount`

---

## 문제 발견: likeCount가 증가하지 않음

### 현재 코드 분석
```java
// PostLikeCommandServiceImpl.createLike()
PostLike postLike = PostLike.create(post, member);
PostLike savedPostlike = postLikeRepository.createPostLike(postLike);
// ← likeCount 증가 로직 없음!
```

- `Post.likeCount`는 `@Builder.Default private Integer likeCount = 0`으로 선언
- 시드 데이터에서 `16_post_count_updates.sql`로 초기값 세팅
- **런타임에서 좋아요/취소 시 likeCount를 갱신하는 코드가 없음**
- `commentCount`도 동일한 문제

### 증명 테스트

**환경**: GCP, 50 VU 동시 좋아요 on Post ID=1

```bash
k6 run --env BASE_URL=http://34.64.214.78:8080 \
       --env TARGET_POST_ID=1 --env VUS=50 \
       k6/concurrency-test.js
```

**결과**:
| 항목 | 값 |
|------|-----|
| 동시 좋아요 성공 | 45건 |
| 중복 감지 (UNIQUE) | 5건 |
| 좋아요 전 like_count | 790 |
| 좋아요 후 like_count | **790 (변화 없음!)** |
| 실제 post_like 레코드 | 835 (790 + 45) |
| **괴리** | **like_count와 실제 레코드 45건 차이** |

> **결론**: likeCount는 시드 데이터 세팅 이후 한 번도 갱신되지 않는다.
> 사용자에게 표시되는 좋아요 수가 실제와 다른 심각한 버그.

---

## Phase 1-2 계획: Lost Update 증명

위 문제를 해결하기 위해 likeCount 증가 로직을 추가해야 하는데,
**의도적으로 안전하지 않은 방식(JPA Dirty Checking)**으로 먼저 구현하여 Lost Update를 증명한다.

### Step 1: JPA Dirty Checking 방식으로 구현 (의도적 취약)
```java
// Post.java
public void incrementLikeCount() { this.likeCount++; }
public void decrementLikeCount() { this.likeCount = Math.max(0, this.likeCount - 1); }
```

### Step 2: 동시 좋아요 테스트 → Lost Update 측정
- VU 10 / 50 / 100 / 200으로 테스트
- 유실률 = (실제 레코드 수 - likeCount) / 실제 레코드 수

### Step 3: DB 원자적 연산으로 전환
```sql
UPDATE post SET like_count = like_count + 1 WHERE id = :postId
```

### Step 4: 동일 테스트 재실행 → 유실률 0% 확인

---

## Lost Update + Deadlock 증명 결과

### JPA Dirty Checking 구현 후 테스트 (50 VU)

`Post.incrementLikeCount()` (this.likeCount++) 추가 후 동시 좋아요 테스트:

```
Deadlock found when trying to get lock; try restarting transaction
[update post set comment_count=?,daily_detail=?,daily_what=?,daily_where=?, ...]
```

**JPA는 전체 컬럼을 UPDATE하므로 동시 접근 시 MySQL InnoDB Deadlock 발생!**

| 항목 | 값 |
|------|-----|
| 동시 좋아요 시도 | 50건 |
| 성공 | **10건** (20%) |
| Deadlock (500 에러) | **39건** (78%) |
| 중복 | 1건 |
| 좋아요 응답시간 avg | 2.15s |

### 정합성 검증
| 항목 | 값 |
|------|-----|
| 테스트 전 like_count | 785 |
| 테스트 후 like_count | **694** |
| 실제 post_like 레코드 | **785** |
| **괴리** | **91건** (like_count가 91 적음) |

### 원인 분석
1. **Deadlock**: JPA Dirty Checking은 `UPDATE post SET comment_count=?, daily_detail=?, ...` 전체 컬럼 업데이트
   → 같은 row에 50개 트랜잭션이 동시 접근 → InnoDB row lock 충돌 → Deadlock
2. **Lost Update**: 성공한 10건도 read-modify-write 패턴으로 like_count를 읽은 시점이 같음
   → 최종 like_count가 실제보다 낮게 기록
3. **삭제 시에도 동일**: teardown의 50건 삭제에서도 decrementLikeCount 경합 발생

### 결론
> **JPA Dirty Checking 기반 카운트 증가는 동시 접근 시 78% Deadlock + Lost Update로 사용 불가.**
> → Phase 2에서 DB 원자적 연산(`UPDATE SET like_count = like_count + 1`)으로 전환 필요.

---

## Phase 1-2 이전: likeCount 미증가 문제

### 테스트 (incrementLikeCount 추가 전, 50 VU)
| 항목 | 값 |
|------|-----|
| 동시 좋아요 성공 | 45건 |
| 중복 감지 | 5건 |
| like_count 변화 | **0** (증가 안 함) |
| 에러율 | 0% |
| 응답시간 avg | 580ms |

> incrementLikeCount 호출 자체가 없으므로 Deadlock도 안 나고 빠르지만, **카운트가 아예 안 맞음.**

---

## Phase 2: 해결 — 이벤트 기반 비동기 원자적 카운트

### 해결 과정 (3단계 시도)

#### 시도 1: DB 원자적 연산 (같은 트랜잭션 내)
```java
// PostRepository에 추가
@Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
void incrementLikeCount(@Param("postId") Long postId);
```
**결과: 여전히 Deadlock (80%)**

원인: `INSERT post_like` (FK → post)가 Post row에 **Shared Lock** 획득 →
같은 트랜잭션 내 `UPDATE post SET like_count` 시 **Exclusive Lock** 필요 →
50개 트랜잭션이 모두 Shared Lock 보유 → 누구도 Exclusive Lock 승격 불가 → **Deadlock**

#### 시도 2: @TransactionalEventListener(AFTER_COMMIT) + @Async
```java
// PostCountEventListener.java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handlePostCountEvent(PostCountEvent event) {
    postRepository.incrementLikeCount(event.getPostId());
}
```
트랜잭션 커밋 후(Shared Lock 해제 후) 별도 트랜잭션에서 카운트 업데이트.

**결과: Deadlock 해소! 0% 에러, 50/50 성공**
하지만 `like_count = 763 vs actual = 785` — **22건 유실**

원인: `@Async` 기본 executor가 `mailExecutor` (core=2, max=5, **queue=10**)
→ 동시 50건 이벤트 중 max 15건만 처리, 나머지 **AbortPolicy로 거절**
→ 거절이 프록시 레벨에서 발생하므로 `try/catch`에 잡히지 않음

#### 시도 3: 전용 countExecutor + CallerRunsPolicy (최종 해결)
```java
// AsyncConfig.java
@Bean(name = "countExecutor")
public ThreadPoolTaskExecutor countExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(200);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
}

// PostCountEventListener.java
@Async("countExecutor")  // 전용 executor 지정
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

### 최종 테스트 결과 (50 VU 동시 좋아요)

| 항목 | JPA Dirty Checking | DB Atomic (같은 TX) | Event + mailExecutor | **Event + countExecutor** |
|------|-------------------|---------------------|---------------------|--------------------------|
| 성공률 | 20% | 20% | **100%** | **100%** |
| Deadlock | 78% | 80% | 0% | **0%** |
| like_count 정합성 | -91 gap | -35 gap | **-22 gap** | **gap = 0** |
| 응답시간 avg | 2.15s | 1.89s | 1.80s | **1.75s** |

### DB 검증
```sql
-- 테스트 후 (50 좋아요 추가 → 50 좋아요 삭제)
SELECT like_count, (SELECT COUNT(*) FROM post_like WHERE post_id=1) as actual
FROM post WHERE id = 1;
-- 결과: like_count = 785, actual = 785, gap = 0 ✅
```

### 아키텍처 요약

```
[좋아요 요청] → [PostLikeCommandService]
  ├─ INSERT post_like (FK → post)
  ├─ publish PostCountEvent
  └─ COMMIT (Shared Lock 해제)
        ↓ (AFTER_COMMIT)
  [countExecutor] → [PostCountEventListener]
        ├─ NEW TRANSACTION
        ├─ UPDATE post SET like_count = like_count + 1 (Exclusive Lock, 경합 없음)
        └─ COMMIT
```

**핵심 설계 결정:**
1. **FK Deadlock 방지**: 카운트 업데이트를 메인 트랜잭션 밖으로 분리
2. **Lost Update 방지**: DB 원자적 연산 (`like_count = like_count + 1`)
3. **이벤트 유실 방지**: 전용 스레드풀 + CallerRunsPolicy
4. **Eventual Consistency**: 카운트는 수 ms 지연되지만, 데이터 무결성 보장

---

## Phase 2-2: 동시성 스케일 테스트 (50 → 100 → 200 → 500 VU)

이벤트 기반 비동기 원자적 카운트 + countExecutor 적용 후 확장 검증.

### 결과 요약

| VU | 성공 | 에러율 | Deadlock | like_count gap | avg 응답시간 | p95 |
|----|------|--------|----------|---------------|-------------|-----|
| 50 | 50/50 | **0%** | 0 | **0** | 1.75s | 2.22s |
| 100 | 100/100 | **0%** | 0 | **0** | 1.81s | 2.59s |
| 200 | 200/200 | **0%** | 0 | **0** | 2.93s | 5.05s |
| 500 | 365/500 | 27% | **0** | N/A* | 7.02s | 14.49s |

*500 VU gap은 teardown 설계 이슈 (실패 유저에게도 decrement 발행) — Deadlock이 아닌 커넥션 풀 한계.

### 500 VU 에러 원인

```
HikariPool-1 - Connection is not available,
request timed out after 5000ms (total=20, active=20, idle=0, waiting=30)
```

- **Deadlock: 0건** — 동시성 버그 완전 해소
- **커넥션 풀 부족**: HikariCP default 20개로 500 동시 요청 처리 불가
- 이는 동시성 정합성 문제가 아닌 **인프라 용량 한계** (커넥션 풀 튜닝으로 해결 가능)

### 결론

> **200 동시 사용자까지 0% 에러, 완벽한 카운트 정합성 (gap = 0) 달성.**
> 500 VU에서의 에러는 Deadlock이 아닌 HikariCP 커넥션 풀 한계 — 인프라 스케일링 영역.
> e2-standard-2 (2 vCPU, 8GB) 단일 VM에서 200 동시 좋아요를 무결하게 처리하는 것은 충분히 의미 있는 성과.
