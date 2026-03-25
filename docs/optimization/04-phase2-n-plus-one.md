# Phase 2: N+1 쿼리 최적화

> 완료일: 2026-02-20

## 작업 범위

피드(Feed) + 댓글(Comments) N+1 문제 해결

---

## 2-1. 피드 TodayQuestion N+1 해결

### 문제

`QueryPostRepositoryImpl.findPostFeed()`에서 각 게시글마다 TodayQuestion을 개별 조회 (루프 내 repository 호출).

```java
// Before: 게시글 10건 → TodayQuestion 10~20회 개별 쿼리
posts.stream().map(postEntity -> {
    todayQuestionRepository.findByMember...(postEntity.getMember(), ...);  // N+1!
})
```

### 해결

1. 모든 게시글의 멤버 ID + 날짜 범위를 수집하여 **1회 배치 쿼리**로 TodayQuestion 일괄 조회
2. `(memberId:questionOrder:yearMonth/date)` 룩업 맵 구성
3. 스트림 매핑 시 룩업 맵에서 O(1) 조회

```java
// After: 1회 배치 쿼리 + 메모리 룩업
List<TodayQuestion> allQuestions = todayQuestionRepository
    .findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(...);
Map<String, TodayQuestion> questionLookup = buildLookupMap(allQuestions);

posts.stream().map(postEntity -> {
    TodayQuestion tq = questionLookup.get(lookupKey);  // O(1) 조회
})
```

4. `@EntityGraph(attributePaths = {"officialQuestion", "personalQuestion"})` 추가하여 `getQuestionContent()` 호출 시 추가 lazy loading 방지

### 변경 파일

- `QueryPostRepositoryImpl.java` — 배치 쿼리 + 룩업 맵 방식으로 전환
- `JpaTodayQuestionRepository.java` — `@EntityGraph` 추가

---

## 2-2. 피드 팔로우 서브쿼리 최적화

Phase 1에서 `idx_follow_follower_circle` 인덱스 추가로 이미 해결됨.
- EXPLAIN: rows:2, filtered:100% (인덱스 풀 활용)
- 추가 코드 변경 불필요

---

## 2-3. 댓글 hasChild/replyCount N+1 해결

### 문제

`CommentQueryServiceImpl.getParentComments()`에서 각 댓글마다 `comment.hasChild()`, `comment.getReplyCount()` 호출 → `replies` 컬렉션 lazy loading 트리거.

```java
// Before: 댓글 10건 → replies 컬렉션 10회 lazy loading
page.getValues().stream()
    .map(comment -> CommentConverter.toCreateResponseWithChildExists(
        comment, comment.hasChild(), comment.isOwner(...)))  // N+1!
```

### 해결

1. 부모 댓글 ID 목록으로 **1회 GROUP BY 쿼리**로 대댓글 수 일괄 조회
2. `Map<Long, Long>` (parentId → replyCount) 구성
3. `CommentConverter`에 replyCount 외부 주입

```java
// After: 1회 배치 쿼리
Map<Long, Long> replyCountMap = commentRepository.countRepliesByParentIds(parentIds);
// 매핑 시 맵에서 조회
long replyCount = replyCountMap.getOrDefault(comment.getId(), 0L);
```

### 변경 파일

- `commentQueryRepositoryImpl.java` — `countRepliesByParentIds()` 구현
- `CommentQueryRepository.java` — 인터페이스 메서드 추가
- `CommentRepository.java` — 도메인 인터페이스 메서드 추가
- `CommentRepositoryAdaptor.java` — 어댑터 구현
- `CommentQueryServiceImpl.java` — 배치 쿼리 사용으로 전환
- `CommentConverter.java` — `toCreateResponseWithChildExists` 시그니처 변경 (replyCount 외부 주입)

---

## 2-4. 댓글 조회 fetchJoin 추가

### 문제

`findParentCommentsByCursor()`, `findRepliesByParentIds()`에서 member, profileImg fetch join 누락 → 각 댓글마다 lazy loading.

### 해결

```java
// Before
queryFactory.selectFrom(comment)
    .where(...)
    .fetch();

// After: member + profileImg fetch join 추가
queryFactory.selectFrom(comment)
    .leftJoin(comment.member, member).fetchJoin()
    .leftJoin(member.profileImg, profileImg).fetchJoin()
    .where(...)
    .fetch();
```

### 변경 파일

- `commentQueryRepositoryImpl.java` — 두 메서드 모두 fetchJoin 추가

---

## 쿼리 수 비교 (p6spy 측정)

| API | Before (Phase 0) | After (Phase 2) | 감소율 |
|-----|-------------------|-----------------|--------|
| **Feed ALL (10건)** | 16 | **7** | **-56%** |
| **Feed CORE (10건)** | 17 | **7** | **-59%** |
| **부모 댓글 (10건)** | ~30+ | **4** | **-87%** |

---

## 부하 테스트 결과

> 테스트 환경: 로컬 (MacOS), MySQL 918MB, 100 VUs, 3분 30초

### 시나리오별 비교

| 시나리오 | Baseline p95 | Phase 1 p95 | Phase 2 p95 | 총 개선율 |
|----------|-------------|-------------|-------------|-----------|
| Feed | 489ms | 245ms | **290ms** | **-40.7%** |
| Profile | 438ms | 130ms | **185ms** | **-57.8%** |
| Notifications | 439ms | 160ms | **187ms** | **-57.4%** |
| Questions | 419ms | 201ms | **262ms** | **-37.5%** |
| Search | 464ms | 177ms | **219ms** | **-52.8%** |

### 전체 지표 비교

| 지표 | Baseline | Phase 1 | Phase 2 | 총 변화 |
|------|----------|---------|---------|---------|
| 에러율 | 0.01% | 0.02% | **0.01%** | 유지 |
| 총 요청 | 20,269 | 22,075 | **21,422** | +5.7% |
| 처리량 | 86 req/s | 93 req/s | **93 req/s** | +8.1% |
| http_req_duration p95 | 518ms | 266ms | **317ms** | **-38.8%** |

### 분석

- **쿼리 수 56~87% 감소**가 Phase 2의 핵심 성과
- p95 응답시간은 Phase 1과 유사한 수준 (배치 쿼리 오버헤드와 N+1 제거가 상쇄)
- 쿼리 수 감소로 **DB 커넥션 점유 시간이 줄어** 고부하 환경에서 더 큰 효과 예상
- 처리량은 Phase 1과 동일하게 93 req/s 유지
