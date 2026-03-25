# Phase 3-1/3-2: 피드 쿼리 최적화 — FORCE INDEX(PRIMARY) 역순 PK 스캔

> 작성일: 2026-03-17
> 환경: GCP e2-standard-2, MySQL 8.0 Docker, Redis 7, HikariCP pool=50

---

## 1. 문제: 기존 피드 쿼리 구조

### 1-1. 기존 QueryDSL 쿼리

```java
jpaQueryFactory
    .selectFrom(post)
    .leftJoin(post.member, member).fetchJoin()
    .leftJoin(member.profileImg, profileImg).fetchJoin()
    .where(condition)        // member_id IN (...) AND status = 'PUBLISHED' AND ...
    .orderBy(post.id.desc())
    .limit(size + 1)
    .fetch();
```

### 1-2. MySQL 실행 계획 (EXPLAIN ANALYZE)

MySQL 옵티마이저가 `idx_post_member_status(member_id, status)` 인덱스를 선택하여:
1. 인덱스에서 member_id IN 조건에 맞는 **모든** 행을 스캔
2. 결과를 id DESC로 정렬 (filesort)
3. LIMIT 적용

| 팔로잉 수 | 실행 시간 | 스캔 행 수 |
|-----------|----------|-----------|
| 50 | 14.7ms | 6,014 |
| 1000 | 57.4ms | 16,509 |
| 2000 | 97.8ms | 28,941 |
| 5000 | 214ms | 61,542 |

**문제**: 팔로잉 수에 비례하여 스캔 행 수와 실행 시간이 선형 증가.

---

## 2. 해결: FORCE INDEX(PRIMARY) 역순 PK 스캔

### 2-1. 핵심 아이디어

MySQL의 PRIMARY KEY는 InnoDB 클러스터 인덱스로 모든 컬럼 데이터를 포함한다.
`FORCE INDEX(PRIMARY)`로 강제하면:

1. MySQL이 PK의 **가장 큰 값(최신 게시글)**부터 역순으로 스캔
2. 각 행에서 `member_id IN (...)` 조건 확인 (HashSet 기반 O(1) 검색)
3. 조건에 맞는 행이 `LIMIT`에 도달하면 **즉시 중단**

팔로잉이 많을수록 최근 게시글에서 매칭 확률이 높아져 스캔이 빨리 종료된다.

### 2-2. EXPLAIN ANALYZE 결과

```sql
SELECT p.id FROM post p FORCE INDEX(PRIMARY)
WHERE p.status = 'PUBLISHED'
  AND p.member_id IN (...)
ORDER BY p.id DESC
LIMIT 11
```

| 팔로잉 수 | 실행 시간 | 스캔 행 수 | 기존 대비 |
|-----------|----------|-----------|----------|
| 50 | 4ms | 4,654 | 3.7x 빠름 |
| 1000 | 0.113ms | 91 | **508x 빠름** |
| 2000 | 0.183ms | 168 | **534x 빠름** |
| 5000 | 0.12ms | 53 | **1,783x 빠름** |

**역전 패턴**: 팔로잉이 많을수록 더 빨라진다 (매칭 확률 ↑, 스캔 범위 ↓).

### 2-3. 구현: 2단계 쿼리 분리

```
Step 1: Native SQL (FORCE INDEX(PRIMARY))
  → 최신 게시글 ID만 빠르게 조회 (JOIN 없음)

Step 2: QueryDSL (post.id.in(postIds))
  → 조회된 ID로 엔티티 + Member + ProfileImg JOIN 페치
```

```java
// Step 1: FORCE INDEX(PRIMARY) 역순 PK 스캔
private List<Long> findPostIdsByReversePKScan(...) {
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT p.id FROM post p FORCE INDEX(PRIMARY)")
       .append(" WHERE p.status = 'PUBLISHED'")
       .append(" AND p.member_id IN (").append(joinIds(allMemberIds)).append(")")
       // ... 공개 범위 + 커서 조건
       .append(" ORDER BY p.id DESC LIMIT ").append(size + 1);

    return entityManager.createNativeQuery(sql.toString())
        .getResultList().stream()
        .map(Number::longValue).collect(Collectors.toList());
}

// Step 2: ID 기반 엔티티 페치
List<Post> posts = jpaQueryFactory
    .selectFrom(post)
    .leftJoin(post.member, member).fetchJoin()
    .leftJoin(member.profileImg, profileImg).fetchJoin()
    .where(post.id.in(postIds))
    .orderBy(post.id.desc())
    .fetch();
```

**왜 QueryDSL 대신 Native SQL인가?**
- QueryDSL/JPA는 `FORCE INDEX` 힌트를 지원하지 않음
- 네이티브 SQL은 ID 조회(step 1)에만 사용, 엔티티 매핑(step 2)은 QueryDSL 유지
- member_id, lastPostId 등은 Long 타입으로 SQL 인젝션 위험 없음

---

## 3. Phase 3-2: 부하 테스트 재측정

### 3-1. 팔로잉 수별 피드 성능 (90 VUs, warm JVM, cold Redis)

| 팔로잉 수 | Before avg | After avg | Before p95 | After p95 |
|-----------|-----------|-----------|-----------|-----------|
| 50 | 1.03s | 306ms | 5.49s | **2.12s** |
| 200 | 1.01s | 300ms | 5.55s | **2.0s** |
| 500 | 1.06s | 299ms | 6.68s | **1.51s** |
| 1000 | 1.02s | 328ms | 7.03s | **2.2s** |
| 2000 | 1.17s | 316ms | 9.57s | **2.12s** |
| 5000 | 1.17s | 321ms | 9.67s | **2.56s** |
| **전체** | **1.08s** | **312ms** | **7.24s** | **2.2s** |

### 3-2. 핵심 지표 개선

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| avg | 1.08s | 312ms | **71% 감소** |
| median | 81ms | 85ms | ~동일 (Redis 캐시 히트) |
| p95 | 7.24s | 2.2s | **70% 감소** |
| p95 (5000명) | 9.67s | 2.56s | **74% 감소** |
| p95 범위 (50→5000) | 5.49s → 9.67s | 2.12s → 2.56s | **4.18s → 0.44s** |
| 에러율 | 0% | 0% | 동일 |
| 처리량 | ~24 req/s | 33.4 req/s | **39% 증가** |

### 3-3. 확장성 곡선 변화

**Before**: 팔로잉 수 증가에 따라 p95가 5.49s → 9.67s로 **76% 악화**
**After**: 팔로잉 수 증가에 따라 p95가 2.12s → 2.56s로 **21% 악화**

→ 팔로잉 수에 대한 성능 감도가 크게 감소. 팔로잉 5000명도 팔로잉 50명과 유사한 성능.

### 3-4. VU 단계적 증가 (50→100→200→500 VU ramp test)

| 지표 | Before (Phase 1-4) | After (Phase 3-2) | 개선율 |
|------|-------------------|-------------------|--------|
| avg | 1.97s | 646ms | **67% 감소** |
| median | 148ms | 56ms | **62% 감소** |
| p95 | 16.35s | 2.75s | **83% 감소** |
| max | N/A | 8.42s | - |
| 에러율 | 0.19% | **0.00%** | **에러 제거** |
| 처리량 | 64.1 req/s | **111.8 req/s** | **74% 증가** |
| 총 요청 | N/A | 82,199 | - |

**500 VU에서도 에러율 0%** — 기존에는 0.19%의 에러가 발생했으나 쿼리 최적화로 DB 커넥션 보유 시간 감소 → 커넥션 풀 경합 완화.

---

## 4. 왜 이 최적화가 효과적인가

### 4-1. 기존 방식의 비효율

```
idx_post_member_status 인덱스 스캔:
  → member_id=1의 모든 게시글 + member_id=2의 모든 게시글 + ... (5000명)
  → 총 61,542행 스캔 → filesort(id DESC) → LIMIT 11
```

대부분의 스캔된 행은 버려진다 (61,542행 중 11행만 사용).

### 4-2. 역순 PK 스캔의 효율

```
PRIMARY KEY 역순 스캔 (가장 최신 게시글부터):
  → row 184,500: member_id=3421 → IN set에 있음? → YES → 결과 1
  → row 184,499: member_id=7821 → IN set에 있음? → YES → 결과 2
  → ...
  → 53행 스캔 후 LIMIT 11 도달 → 즉시 종료
```

팔로잉 5000명 = 전체 회원 10,000명 중 50%. 최근 게시글 2개 중 1개는 팔로잉한 사람의 글.

### 4-3. 비용-효과 분석

- **코드 변경**: QueryDSL 쿼리 1개 → Native SQL + QueryDSL 2단계
- **복잡도 증가**: 최소 (순수 SQL, JPA EntityManager 활용)
- **기능 변경**: 없음 (동일한 결과, 동일한 페이지네이션)
- **성과**: p95 70% 감소, 처리량 39% 증가

---

## 5. 포트폴리오 요약

```
[피드 쿼리 최적화]

문제:
  → 팔로잉 5000명 시 피드 쿼리 214ms, 61,542행 스캔
  → p95 응답 시간 9.67s (90 VU 동시 접속)

분석:
  → EXPLAIN ANALYZE로 인덱스 선택 문제 발견
  → MySQL이 member_status 인덱스로 전체 매칭 후 정렬하는 비효율 패턴

해결:
  → FORCE INDEX(PRIMARY) 역순 PK 스캔으로 전환
  → 2단계 쿼리 분리 (ID 조회 → 엔티티 페치)

성과:
  → 쿼리 실행 시간: 214ms → 0.12ms (1,783배 개선)
  → 피드 p95: 7.24s → 2.2s (70% 감소)
  → VU ramp p95: 16.35s → 2.75s (83% 감소)
  → 처리량: 64.1 → 111.8 req/s (74% 증가)
  → 에러율: 0.19% → 0% (500 VU에서도 무장애)
  → 팔로잉 수 성능 감도: 76% → 21% (확장성 개선)
```
