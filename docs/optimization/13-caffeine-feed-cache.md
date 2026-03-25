# 13. Caffeine 피드 캐시 도입 결과

> 날짜: 2026-03-13
> 관련 코드: `PostQueryServiceImpl.findPostFeed()`, `CacheConfig.java`

---

## 배경

피드 조회 시 단일 요청은 150ms이지만, 90 VU 동시 요청에서 avg 6.81s로 40배 증가.
원인: 2 vCPU 서버에서 90개 요청이 CPU를 경합. `150ms × (90/2 CPU) ≈ 6.75s` — 이론값과 일치.

**접근**: 요청당 CPU 사용을 줄이기 위해 피드 결과를 Caffeine(로컬 JVM 캐시)에 캐싱.

---

## 구현

### 캐시 설계
- **대상**: 첫 페이지(커서 없음) 피드만 캐시
- **키**: `{memberId}:{feedType}` (예: `4576:ALL`)
- **TTL**: 30초 (피드는 자주 변경되므로 짧은 TTL)
- **최대 크기**: 10,000 엔트리
- **무효화**: 본인 글 작성 시 즉시 evict + TTL 자동 만료

### 메모리 예상
- 피드 DTO 1건 ≈ 5KB (PostSummary × 10개)
- 10K 유저 전체 캐시 시 ≈ **50MB** (4GB 힙의 1.25%)

### 코드 변경
```java
// CacheConfig.java — feedCache 추가 (TTL 30초, max 10000)
buildCache("feedCache", 10000, 30, TimeUnit.SECONDS)

// PostQueryServiceImpl.java — 첫 페이지 캐시 적용
if (isFirstPage) {
    Cache feedCache = cacheManager.getCache("feedCache");
    cached = feedCache.get(cacheKey, PostFeedResponseDTO.class);
    if (cached != null) return cached;  // 캐시 히트 → DB 스킵
    // 미스 → DB 조회 후 캐시 저장
}
```

---

## 측정 결과

### 단일 요청 (경합 없음)
| 요청 | 캐시 전 | 캐시 후 (cold) | 캐시 후 (warm) |
|------|---------|---------------|---------------|
| 응답시간 | 150ms | 2.0s (JIT 미완) | **75~100ms** |

### 90 VU 동시 부하 테스트

| 지표 | 캐시 전 | Caffeine 캐시 후 | 개선율 |
|------|---------|-----------------|--------|
| **avg 응답시간** | 6.81s | **2.03s** | **-70%** |
| **median** | 6.60s | **0.65s** | **-90%** |
| p95 | 11.24s | 9.53s | -15% |
| **에러율** | 23.33% | **4.33%** | **-81%** |
| **처리량** | 8.3 req/s | **19.4 req/s** | **+134%** |
| 총 소요시간 | 1m48s | 0m46s | -57% |

### 팔로잉 수별 결과 (Caffeine 캐시 적용 후)
| 팔로잉 수 | avg | median | p95 |
|-----------|-----|--------|-----|
| 50 | 1.99s | 720ms | 9.23s |
| 200 | 1.99s | 793ms | 9.27s |
| 500 | 2.02s | 739ms | 8.03s |
| 1000 | 2.03s | 647ms | 8.47s |
| 2000 | 2.05s | 656ms | 9.77s |
| 5000 | 2.13s | 497ms | 12.40s |

→ **팔로잉 수에 관계없이 median 500~800ms로 균일화** (캐시 히트 시 팔로잉 수 무관)

---

## Caffeine의 한계 (Redis 전환이 필요한 이유)

### 1. p95가 여전히 높음 (9.53s)
캐시 히트 시 빠르지만, **cold miss 시 90 VU가 동시에 DB를 직접 쿼리** → CPU 경합 재발.
TTL 30초마다 모든 캐시가 만료되면서 주기적으로 spike가 발생.

### 2. Thundering Herd 문제
캐시 만료 순간 같은 유저의 피드를 요청하는 여러 스레드가 동시에 DB 쿼리를 실행.
Caffeine 자체는 `Cache.get(key, loader)` 패턴으로 방어 가능하지만,
Spring `@Cacheable`은 기본적으로 이를 보장하지 않음.

### 3. 스케일 아웃 불가
인스턴스 2대 이상 배포 시:
- 각 JVM이 별도 캐시 보유 → 캐시 불일치
- A서버에서 글 작성 → B서버 캐시에 반영 안 됨
- 유저가 새로고침할 때마다 다른 결과를 볼 수 있음

### 4. GC 압박 가능성
피드 DTO가 Java 객체로 Old Generation에 장기 상주 → 유저 수 증가 시 Full GC 빈번.
현재 50MB(1.25%)는 문제없지만, 10만 유저가 되면 500MB로 힙의 12.5% 차지.

### 5. 메모리 상한
JVM 힙은 앱과 공유 → 캐시가 커지면 OOM 위험.
Redis는 별도 프로세스로 독립적인 메모리 관리.

---

## 결론

> **Caffeine 캐시로 median 90% 개선, 처리량 2.3배 증가 — 로컬 캐시만으로도 큰 효과.**
> 하지만 p95 미개선, Thundering Herd, 스케일 아웃 불가 등의 한계가 있어
> **다음 단계로 Redis 전환이 필요.**
>
> Redis 전환 시 기대 효과:
> - Cache Aside + 분산 캐시로 모든 인스턴스 일관성 보장
> - Redis 전용 인스턴스로 JVM 힙 부담 제거
> - p95 개선 (Redis에서 바로 반환, DB 경합 최소화)
