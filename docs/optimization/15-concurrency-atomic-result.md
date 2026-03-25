# Phase 2: 동시성 정합성 확보 — DB 원자적 연산 결과

> 테스트 일시: 2026-03-16
> 환경: GCP e2-standard-2, MySQL 8.0 Docker, HikariCP pool=50
> 도구: k6 concurrency-test.js

---

## 1. Before: Phase 1-2에서 증명한 문제

JPA Dirty Checking 방식 (`Post.likeCount += 1`)으로 카운트를 갱신할 때:

| 지표 | 결과 |
|------|------|
| 동시 좋아요 50건 | Deadlock 78%, Lost Update 91건 괴리 |
| 원인 | JPA 전체 컬럼 UPDATE → InnoDB Row Lock 충돌 |
| 문서 | `docs/optimization/11-concurrency-test.md` |

**문제**: JPA의 `SELECT → 수정 → UPDATE` 흐름은 동시 접근 시 Lost Update 발생.

---

## 2. 해결: DB 원자적 연산 + 비동기 이벤트 아키텍처

### 2-1. 구현 구조

```
Service Layer (좋아요 생성/삭제)
  └→ ApplicationEventPublisher.publishEvent(PostCountEvent)
       └→ @TransactionalEventListener(phase = AFTER_COMMIT)
            └→ @Async("countExecutor")
                 └→ @Transactional(propagation = REQUIRES_NEW)
                      └→ UPDATE Post SET likeCount = likeCount + 1 WHERE id = ?
```

**핵심 설계 결정:**
- **DB 원자적 연산**: `SET count = count + 1` — DB가 원자성 보장, Lost Update 원천 차단
- **AFTER_COMMIT**: 좋아요 INSERT 트랜잭션 커밋 후 카운트 업데이트 → FK Deadlock 방지
- **REQUIRES_NEW**: 별도 트랜잭션으로 실행 → 카운트 실패가 좋아요 INSERT에 영향 없음
- **@Async**: 메인 요청 응답 시간에 영향 없음

### 2-2. 구현 코드

```java
// JpaPostRepository — 원자적 카운트 연산
@Modifying
@Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
void incrementLikeCount(@Param("postId") Long postId);

@Modifying
@Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
void decrementLikeCount(@Param("postId") Long postId);
```

```java
// PostLikeCommandServiceImpl — 좋아요 생성
postLikeRepository.createPostLike(postLike);
eventPublisher.publishEvent(PostCountEvent.likeIncrement(postId));

// PostLikeCommandServiceImpl — 좋아요 삭제 (방어 코드 포함)
if (!postLikeRepository.existsByMemberAndPost(member, post)) {
    throw new LikeHandler(ErrorStatus.POST_LIKE_NOT_FOUND);
}
postLikeRepository.deleteByPostAndMember(post, member);
eventPublisher.publishEvent(PostCountEvent.likeDecrement(postId));
```

### 2-3. countExecutor 설정

```java
@Bean(name = "countExecutor")
public ThreadPoolTaskExecutor countExecutor() {
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(200);
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
}
```

---

## 3. After: 동시성 테스트 결과

### 3-1. Phase 2-2: 100 VU 동시 좋아요

| 지표 | 결과 |
|------|------|
| 동시 VU | 100 |
| 좋아요 성공 | 88~92건 |
| 중복 감지 (UNIQUE) | 8~12건 |
| 에러 | **0건 (0%)** |
| Deadlock | **0건** |
| like_count vs actual diff | **0 (완벽 정합)** |
| 응답 시간 avg | 820ms~965ms |
| 응답 시간 p95 | 1.24s~1.42s |

**DB 직접 검증:**
```sql
-- Post 500: 테스트 전후
-- Before: like_count=749, actual_likes=749
-- After cleanup: like_count=737, actual_likes=737, diff=0 ✓
```

### 3-2. Phase 2-3: 스케일 테스트 (100 → 200 → 500 VU)

| VU | 에러율 | post_like 레코드 | like_count diff | 응답 avg | 응답 p95 |
|----|-------|-----------------|-----------------|---------|---------|
| 100 | **0%** | 정확 | **0** | 820ms | 1.24s |
| 200 | **0%** | 정확 | **0** | 1.47s | 2.55s |
| 500 | 23.4% | 정확 | -142 (이벤트 유실) | 7.23s | 15.43s |

### 3-3. InnoDB Row Lock 통계

| 지표 | 값 |
|------|---|
| Row Lock 평균 대기 | **54~59ms** |
| Row Lock 최대 대기 | 320ms |
| Row Lock 발생 횟수 | 1,325~1,818회 |
| 현재 Lock 대기 | 0 |

---

## 4. 분석

### 4-1. Before/After 비교

| 지표 | Before (JPA Dirty Checking) | After (DB 원자적 연산) |
|------|---------------------------|---------------------|
| Deadlock 발생률 | 78% | **0%** |
| Lost Update | 91건 괴리 | **0건** |
| 에러율 (100 VU) | 높음 | **0%** |
| Row Lock 대기 | N/A (Deadlock) | avg 54ms |

### 4-2. 500 VU 분석

500 VU에서 에러율 23.4%와 like_count diff=-142가 발생한 원인:

1. **에러의 원인**: 서버 용량 한계 (HikariCP pool=50에 500 동시 요청)
2. **diff의 원인**: 비동기 이벤트 큐 초과 (countExecutor queueCapacity=200)
3. **post_like 레코드는 정확**: 레코드 자체에는 유실 없음
4. **결론**: 500 VU는 **서버 인프라 한계**이지, 원자적 연산의 한계가 아님

### 4-3. 테스트 중 발견한 버그

- **deleteLike 방어 코드 부재**: 좋아요가 없는 유저에게 delete 요청 시 decrement 이벤트가 발행됨
- **수정**: `existsByMemberAndPost()` 체크 추가, `POST_LIKE_NOT_FOUND` 에러 반환

---

## 5. 결론: Phase 2-4, 2-5 SKIP 근거

### 왜 3가지 락 전략 비교가 불필요한가 (Phase 2-4 SKIP)

- DB 원자적 연산의 Row Lock avg=54ms — 충분히 양호
- 비관적 락은 SELECT FOR UPDATE로 트랜잭션 기간 동안 Lock 유지 → 더 느림
- 낙관적 락은 충돌 시 재시도 필요 → 인기 글에서 불리
- `UPDATE SET count = count + 1`이 가장 단순하고 효율적

### 왜 Redis 카운터가 불필요한가 (Phase 2-5 SKIP)

- 현실적 시나리오: SNS 다이어리 앱에서 **동시 200명이 같은 글에 좋아요**를 누르는 상황은 극히 드묾
- DB 원자적 연산으로 200 VU까지 **에러 0%, 정합성 100%**
- Redis 카운터 도입 시 추가 복잡도:
  - Redis↔DB 동기화 배치
  - Eventual Consistency (최대 5분 차이)
  - Redis 장애 시 폴백 로직
- **측정 결과가 Redis 불필요를 증명**: "DB로 충분한데 왜 Redis를 쓰는가?"

> "동시 좋아요 200건까지 DB 원자적 연산으로 p95 < 2.55s, 유실률 0%를 유지했습니다.
> 현재 서비스 규모에서 이 한계를 넘는 경우는 비현실적이므로 DB 원자적 연산으로 충분합니다.
> Redis 카운터는 이 한계를 넘어설 때 도입합니다."

---

## 6. 포트폴리오 요약

```
[동시성 정합성]

문제 발견:
  → 좋아요/댓글 카운트 갱신 로직 부재 → JPA Dirty Checking 추가 시 Deadlock 78%

해결:
  → DB 원자적 연산 (UPDATE SET count = count + 1)
  → 비동기 이벤트 아키텍처 (AFTER_COMMIT + REQUIRES_NEW)
  → deleteLike 방어 코드 추가 (테스트에서 발견한 버그)

성과:
  → Deadlock 78% → 0%
  → Lost Update 91건 → 0건
  → 동시 좋아요 200건까지 에러 0%, 정합성 100%
  → Row Lock 평균 대기 54ms (DB 부담 최소)
```
