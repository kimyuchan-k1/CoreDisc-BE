# 14. Redis 피드 캐시 + Thundering Herd 방어

> 날짜: 2026-03-13
> 관련 코드: `FeedCacheService.java`, `PostQueryServiceImpl.findPostFeed()`

---

## Caffeine → Redis 전환 배경

Caffeine 캐시로 median 90% 개선을 달성했으나 3가지 한계 존재:

1. **p95 미개선**: TTL 만료 시 90 VU가 동시에 DB 쿼리 (Thundering Herd)
2. **스케일 아웃 불가**: 인스턴스별 별도 캐시 → 캐시 불일치
3. **GC 압박**: 피드 DTO가 Java 힙에 상주 → 유저 수 증가 시 Full GC 유발

---

## 구현

### 아키텍처: Cache Aside + 분산 락

```
[피드 요청] → Redis GET feed:{memberId}:{feedType}
  ├─ HIT → 즉시 반환 (< 1ms)
  └─ MISS → SETNX feed:lock:{memberId}:{feedType}
              ├─ 락 획득 → DB 조회 → Redis SET (TTL 30초) → 반환
              └─ 락 미획득 → 50ms 간격 폴링 (최대 500ms) → 캐시 재조회
                              └─ 타임아웃 → fallback DB 직접 조회
```

### Thundering Herd 방어 흐름

```
T=0초: 캐시 만료
Thread-1: MISS → tryLock() 성공 → DB 조회 시작
Thread-2: MISS → tryLock() 실패 → waitAndGet() 대기
Thread-3: MISS → tryLock() 실패 → waitAndGet() 대기
...
T=0.15초: Thread-1 DB 조회 완료 → Redis SET → unlock
T=0.2초: Thread-2 waitAndGet() → 캐시 히트 반환
T=0.2초: Thread-3 waitAndGet() → 캐시 히트 반환

→ DB 쿼리 1회로 90개 요청 처리 (vs Caffeine: 최대 90회 동시 DB 쿼리)
```

### 핵심 코드

```java
// FeedCacheService.java
public boolean tryLock(Long memberId, String feedType) {
    String lockKey = LOCK_KEY_PREFIX + memberId + ":" + feedType;
    return Boolean.TRUE.equals(
        redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS));
}

public PostFeedResponseDTO waitAndGet(Long memberId, String feedType) {
    for (int i = 0; i < 10; i++) {  // 최대 500ms 대기
        Thread.sleep(50);
        PostFeedResponseDTO cached = get(memberId, feedType);
        if (cached != null) return cached;
    }
    return null;  // 타임아웃 → caller가 직접 DB 조회
}
```

### Redis 장애 대비 (Graceful Fallback)

모든 Redis 연산은 try/catch로 감싸여 있음:
- Redis 다운 시 → `tryLock()`이 항상 true 반환 → DB 직접 조회
- 직렬화 실패 시 → 로그 경고 후 캐시 없이 동작
- **Redis 장애가 서비스 장애로 이어지지 않음**

---

## 측정 결과 (90 VU 동시 부하)

### 3단계 진화 비교

| 지표 | 캐시 없음 | Caffeine | **Redis + 분산 락** |
|------|----------|----------|---------------------|
| **avg 응답시간** | 6.81s | 2.03s | **1.96s** |
| **median** | 6.60s | 0.65s | **0.72s** |
| **p95** | 11.24s | 9.53s | **8.05s** |
| **에러율** | 23.33% | 4.33% | **3.33%** |
| **처리량** | 8.3 req/s | 19.4 req/s | **20.5 req/s** |

### Redis의 차별점: p95 개선

Caffeine p95 = 9.53s → Redis p95 = **8.05s** (15% 개선)

원인: Thundering Herd 방어
- Caffeine: TTL 만료 시 N개 스레드가 동시 DB 조회 → CPU 경합
- Redis: 락을 건 1개 스레드만 DB 조회 → 나머지 N-1개는 Redis 대기 → CPU 경합 최소화

### 팔로잉 수별 결과 (Redis 캐시)

| 팔로잉 수 | avg | median | p95 |
|-----------|-----|--------|-----|
| 50 | 1.90s | 750ms | 6.79s |
| 200 | 1.92s | 772ms | 7.15s |
| 500 | 1.91s | 820ms | 6.73s |
| 1000 | 1.96s | 745ms | 7.59s |
| 2000 | 1.99s | 699ms | 9.60s |
| 5000 | 2.10s | 435ms | 11.10s |

→ 캐시 히트 시 팔로잉 수 무관하게 **median 400~800ms로 균일**

---

## 캐시 전 vs 후 전체 개선율

| 지표 | 개선 전 (캐시 없음) | 개선 후 (Redis) | 개선율 |
|------|-------------------|----------------|--------|
| avg | 6.81s | 1.96s | **-71%** |
| median | 6.60s | 0.72s | **-89%** |
| p95 | 11.24s | 8.05s | **-28%** |
| 에러율 | 23.33% | 3.33% | **-86%** |
| 처리량 | 8.3 req/s | 20.5 req/s | **+147%** |

---

## Redis 전용 인스턴스 분리 (향후)

현재 같은 VM에 App + MySQL + Redis가 공존.
Redis를 별도 인스턴스로 분리하면:

1. **CPU 경합 해소**: Redis 단독 CPU → 응답 안정성 향상
2. **메모리 독립**: Redis가 필요한 만큼 메모리 확보 가능
3. **수평 확장 준비**: App 인스턴스 추가 시 공유 Redis로 캐시 일관성 유지
