# Phase 4: 캐싱 레이어 (Caffeine Local Cache)

> 완료일: 2026-02-20

## 작업 범위
피드 조회 시 팔로잉/서클 ID를 매번 DB에서 조회하는 대신, Caffeine 로컬 캐시로 캐싱하여 반복 조회를 제거.

---

## 4-1. Caffeine + Spring Cache 설정

### 변경 내용
- `build.gradle`에 의존성 추가: `spring-boot-starter-cache`, `caffeine`
- `CacheConfig.java` 생성: `@EnableCaching`, CaffeineCacheManager

### 캐시 설정
```java
CaffeineCacheManager manager = new CaffeineCacheManager("followingIds", "circleIds");
manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats());
```

| 설정 | 값 | 이유 |
|------|----|------|
| maximumSize | 5000 | 동시 사용자 수 기준 충분한 캐시 크기 |
| expireAfterWrite | 5분 | 팔로우 변경 빈도 대비 적절한 TTL |
| recordStats | true | 캐시 히트율 모니터링 |

### 변경 파일
- `build.gradle` — 의존성 추가
- `CacheConfig.java` — **새 파일**

---

## 4-2. 팔로잉/서클 ID 목록 캐싱

### Before (서브쿼리)
```
[피드 요청] → [메인 쿼리 (inline 서브쿼리로 follow 테이블 매번 조회)]
              └─ SELECT following_id FROM follow WHERE follower_id=? (서브쿼리 1)
              └─ SELECT following_id FROM follow WHERE follower_id=? AND is_circle=true (서브쿼리 2, 3)
```

### After (캐시된 ID 리스트)
```
[피드 요청] → [캐시 조회 (followingIds, circleIds)]
              ├─ HIT  → ID 리스트 즉시 반환 (DB 조회 없음)
              └─ MISS → DB 조회 후 캐시 저장
           → [메인 쿼리 (WHERE member_id IN (:cachedIds))]
```

### 구현

**1. QueryFollowRepository — ID 조회 메서드 추가**
- `findFollowingIds(Long memberId)` — 내가 팔로우하는 모든 사용자 ID
- `findCircleFollowingIds(Long memberId)` — 서클(친한친구)로 설정된 사용자 ID

**2. FollowQueryService — @Cacheable 적용**
```java
@Cacheable(value = "followingIds", key = "#memberId")
public List<Long> getFollowingIds(Long memberId)

@Cacheable(value = "circleIds", key = "#memberId")
public List<Long> getCircleFollowingIds(Long memberId)
```

**3. QueryPostRepositoryImpl — 서브쿼리 제거**
- `findPostFeed()` 시그니처에 `followingIds`, `circleIds` 파라미터 추가
- 기존 inline JPAQueryFactory 서브쿼리 3개 → `post.member.id.in(followingIds)` / `post.member.id.in(circleIds)` 로 교체
- 결과: 메인 쿼리에서 follow 테이블 JOIN 완전 제거

**4. PostQueryServiceImpl — 캐시 호출 및 전달**
```java
List<Long> followingIds = followQueryService.getFollowingIds(member.getId());
List<Long> circleIds = followQueryService.getCircleFollowingIds(member.getId());
postRepository.findPostFeed(member, feedType, lastPostId, size, followingIds, circleIds);
```

**5. FollowCommandServiceImpl — @CacheEvict 적용**
```java
@CacheEvict(value = "followingIds", key = "#member.id")
public Follow follow(Member member, Long targetId)

@Caching(evict = {
    @CacheEvict(value = "followingIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public void unfollow(Member member, Long targetId)

@CacheEvict(value = "circleIds", key = "#targetId")
public void updateCircleStatus(Member member, Long targetId, boolean isCircle)
```

### 변경 파일

| 파일 | 작업 |
|------|------|
| `QueryFollowRepository.java` | ID 조회 메서드 추가 |
| `QueryFollowRepositoryImpl.java` | 구현 |
| `FollowQueryService.java` | 인터페이스 추가 |
| `FollowQueryServiceImpl.java` | `@Cacheable` 메서드 추가 |
| `PostRepository.java` (도메인) | `findPostFeed` 시그니처 변경 |
| `PostRepositoryAdaptor.java` | 시그니처 변경 |
| `QueryPostRepository.java` | 시그니처 변경 |
| `QueryPostRepositoryImpl.java` | 서브쿼리 → IN 절 교체 |
| `PostQueryServiceImpl.java` | FollowQueryService 주입, 캐시 호출 |
| `FollowCommandServiceImpl.java` | `@CacheEvict` 추가 |

---

## 쿼리 수 개선

| 상태 | 쿼리 수 | 설명 |
|------|---------|------|
| Phase 2 (서브쿼리 포함) | 7 | 메인 쿼리 내 follow 서브쿼리 3개 포함 |
| Phase 4 Cache MISS | 9 | follow ID 2개 쿼리 추가, 서브쿼리 제거 |
| Phase 4 Cache HIT | 7 | follow ID 쿼리 0개 (캐시), 서브쿼리 없음 |

핵심: Cache HIT 시 메인 쿼리에서 follow 테이블 접근 완전 제거. 단순 IN 절 처리로 쿼리 복잡도 감소.

---

## 부하 테스트 결과

> 테스트 환경: 로컬 (MacOS), MySQL 918MB, 100 VUs, 3분 30초

### 시나리오별 비교

| 시나리오 | Baseline p95 | Phase 3 p95 | Phase 4 p95 | 총 개선율 |
|----------|-------------|-------------|-------------|-----------|
| Feed | 489ms | 262ms | **246ms** | **-49.7%** |
| Profile | 438ms | 141ms | **127ms** | **-71.0%** |
| Notifications | 439ms | 154ms | **167ms** | **-61.9%** |
| Questions | 419ms | 190ms | **197ms** | **-53.0%** |
| Search | 464ms | 159ms | **188ms** | **-59.5%** |

### 전체 지표 비교

| 지표 | Baseline | Phase 3 | Phase 4 | 총 변화 |
|------|----------|---------|---------|---------|
| 에러율 | 0.01% | 0.01% | **0.03%** | 유지 |
| 총 요청 | 20,269 | 21,912 | **21,795** | +7.5% |
| 처리량 | 86 req/s | 94 req/s | **94 req/s** | +9.3% |
| http_req_duration p95 | 518ms | 270ms | **296ms** | **-42.9%** |

### 분석

1. **Feed p95 개선**: 262ms → 246ms (-6.1%). 캐시 히트 시 follow 서브쿼리 완전 제거 효과.
2. **Profile p95 개선**: 141ms → 127ms (-9.9%). DB 커넥션 경합 감소 간접 효과.
3. **전체 p95 유사**: 인덱스가 잘 잡힌 상태에서 서브쿼리 자체가 이미 빠르기 때문에 극적 차이는 없음.
4. **캐시의 진짜 가치**: DB 커넥션 점유 시간 감소, 더 높은 동시 사용자 수에서의 안정성, DB 부하 감소.

---

## 설계 결정

### 왜 Caffeine (로컬 캐시) 인가?
- **단일 서버 구성**: 현재 아키텍처에서 분산 캐시(Redis) 불필요
- **초저지연**: 네트워크 없이 힙 메모리 접근 (~나노초)
- **Spring Cache 통합**: `@Cacheable`/`@CacheEvict`로 깔끔한 AOP 적용
- **TTL + Size-based eviction**: 5분 만료 + 5000 엔트리 제한으로 메모리 안전

### 왜 서브쿼리를 IN 절로 바꿨는가?
- **캐시 친화적**: ID 리스트를 캐시 가능한 형태로 분리
- **쿼리 독립성**: follow 테이블과 post 테이블 쿼리 분리로 각각 최적화 가능
- **DB 접근 패턴**: 캐시 히트 시 follow 테이블 접근 0회

---

## 4-3. 게시글 상세 조회 — 질문 컨텐츠 배치 쿼리 최적화

### Before (4개 개별 쿼리)
```java
// PostQueryServiceImpl.findQuestionContent()
for(int i = 1; i < 4; i++) {
    todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween(member, i, startOfMonth, endOfMonth)
            .ifPresent(questions::add);  // 쿼리 1, 2, 3
}
todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDate(member, 4, date)
        .ifPresent(questions::add);  // 쿼리 4
```
- 월간 고정질문 3개: 각각 개별 쿼리 (3회)
- 일간 질문 1개: 개별 쿼리 (1회)
- **총 4 쿼리**, 각 쿼리가 DB 커넥션 점유

### After (1개 배치 쿼리)
```java
List<TodayQuestion> allQuestions = todayQuestionRepository
    .findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
        List.of(member.getId()),
        List.of(1, 2, 3, 4),
        startOfMonth, endOfMonth
    );

// questionOrder 4는 해당 날짜만 필터, 1~3은 월 범위 내 첫 번째만 사용
return allQuestions.stream()
    .filter(q -> q.getQuestionOrder() != 4 || q.getSelectedDate().equals(date))
    .toList();
```
- **@EntityGraph**로 officialQuestion/personalQuestion 즉시 로딩 (N+1 방지)
- **1개 배치 쿼리** + 메모리 필터링

### 설계 결정
- 기존 배치 메서드(`findByMemberIdInAndQuestionOrderInAndSelectedDateBetween`)를 재활용
- 이 메서드는 피드 배치 최적화(Phase 2)에서 이미 만들어져 있었음
- 새 메서드 생성 없이 단일 멤버 ID를 List로 감싸 호출

### 변경 파일

| 파일 | 작업 |
|------|------|
| `PostQueryServiceImpl.java` | `findQuestionContent()` 배치 쿼리로 교체 |

---

## 4-4. 게시글 상세 조회 — 좋아요 체크 쿼리 최적화

### Before (3 쿼리 — 엔티티 로딩)
```java
private boolean checkIsLiked(Long memberId, Long postId) {
    Member member = memberRepository.findById(memberId)   // 쿼리 1: Member 엔티티 로딩
            .orElseThrow(...);
    Post post = postRepository.findById(postId)           // 쿼리 2: Post 엔티티 로딩
            .orElseThrow(...);
    return postLikeRepository.existsByMemberAndPost(member, post);  // 쿼리 3: 존재 여부 확인
}
```
- `existsByMemberAndPost(Member, Post)`는 JPA 엔티티를 파라미터로 요구
- 이미 ID를 알고 있는데도 엔티티를 **불필요하게 로딩**

### After (1 쿼리 — ID 기반)
```java
private boolean checkIsLiked(Long memberId, Long postId) {
    return postLikeRepository.existsByMemberIdAndPostId(memberId, postId);  // 쿼리 1회
}
```
- Spring Data JPA 네이밍 컨벤션: `existsByMember_IdAndPost_Id` → 자동으로 `member_id`와 `post_id` FK 컬럼 사용
- **엔티티 로딩 없이** 직접 EXISTS 쿼리 실행

### 변경 파일

| 파일 | 작업 |
|------|------|
| `PostLikeRepository.java` (도메인) | `existsByMemberIdAndPostId` 메서드 추가 |
| `JpaPostLikeRepository.java` | 동일 메서드 추가 |
| `PostLikeRepositoryAdaptor.java` | 구현 추가 |
| `PostQueryServiceImpl.java` | `checkIsLiked()` ID 기반으로 변경, `MemberRepository` 의존성 제거 |

---

## 4-3/4-4 쿼리 수 개선 (게시글 상세 조회)

| 항목 | Before | After | 감소 |
|------|--------|-------|------|
| 질문 컨텐츠 조회 | 4 쿼리 | 1 쿼리 | -75% |
| 좋아요 체크 | 3 쿼리 | 1 쿼리 | -67% |
| **상세 조회 전체** | **~10+ 쿼리** | **~6 쿼리** | **-40%** |

### 검증 방법
단건 API 호출 후 p6spy 로그 카운트로 쿼리 수 측정:
```bash
# 로그 초기화 후 단건 호출
: > /tmp/coredisc-app.log
curl -s "http://localhost:8080/api/posts/{postId}" -H "Authorization: Bearer $TOKEN"
# p6spy statement 카운트 (prepared + actual이므로 /2)
strings /tmp/coredisc-app.log | grep -c "| statement |"  # 12 → 실제 6 쿼리
```
