# 캐시 무효화 엣지 케이스 수정 체크리스트

> Caffeine 캐시 도입 후 발견된 캐시 무효화 누락 이슈
> 팔로우/서클/차단 상태 변경 시 캐시가 제대로 무효화되지 않는 경우가 있음

---

## 현재 캐시 구조

```
CacheConfig.java
├── followingIds  — 특정 사용자가 팔로우하는 사람들의 ID 리스트
└── circleIds     — 특정 사용자의 서클(친한친구) ID 리스트
```

캐시 조회: `FollowQueryServiceImpl` (getFollowingIds, getCircleFollowingIds)
캐시 사용: `PostQueryServiceImpl.findPostFeed()` → 피드 WHERE 절 IN 조건

---

## 이슈 1: block() — 캐시 무효화 완전 누락 (심각도: 높음)

### 파일: `BlockCommandServiceImpl.java` (26~56행)

### 현재 상태
```java
@Override
public Block block(Member member, Long targetId) {
    // 팔로우 관계 삭제 (42~51행)
    Follow followToTarget = followRepository.findByFollowerAndFollowing(member, target);
    if (followToTarget != null) {
        followRepository.delete(followToTarget);  // DB에서 삭제
    }
    Follow followFromTarget = followRepository.findByFollowerAndFollowing(target, member);
    if (followFromTarget != null) {
        followRepository.delete(followFromTarget);  // DB에서 삭제
    }
    // ⚠️ @CacheEvict 없음 — 캐시에는 여전히 이전 팔로우 정보가 남아 있음
}
```

### 문제
A가 B를 차단하면:
1. DB에서 A→B, B→A 팔로우 관계가 삭제됨
2. **하지만 캐시에는 여전히 B가 A의 followingIds에 남아 있음**
3. A의 피드에 차단한 B의 게시글이 TTL(5분) 동안 계속 노출됨

### 수정 방법
- [x] `block()` 메서드에 `@Caching` 추가

```java
@Caching(evict = {
    @CacheEvict(value = "followingIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "followingIds", key = "#targetId"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public Block block(Member member, Long targetId) { ... }
```

> 양방향 팔로우가 모두 삭제되므로 양쪽 사용자의 followingIds + circleIds 모두 무효화 필요

---

## 이슈 2: updateCircleStatus() — circleIds[member] 무효화 누락 (심각도: 중간)

### 파일: `FollowCommandServiceImpl.java` (137~163행)

### 현재 상태
```java
@Override
@CacheEvict(value = "circleIds", key = "#targetId")  // target만 무효화
public void updateCircleStatus(Member member, Long targetId, boolean isCircle) { ... }
```

### 문제
A가 B를 서클에 추가/제거하면:
- `circleIds[targetId(B)]`만 무효화됨
- **`circleIds[member(A)]`는 무효화되지 않음**
- A의 CORE 피드에서 서클 변경이 즉시 반영되지 않을 수 있음

### 수정 방법
- [x] `@CacheEvict` → `@Caching` 으로 변경

```java
@Caching(evict = {
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public void updateCircleStatus(Member member, Long targetId, boolean isCircle) { ... }
```

---

## 이슈 3: 캐시 무효화 즉시 동작 문서 명시 (심각도: 낮음 — 문서 보완)

### 대상: `docs/portfolio/feed-comment-optimization.md` Phase 4 캐시 무효화 섹션

### 현재 상태
TTL 5분 설정은 문서에 있지만, `@CacheEvict`가 TTL과 별개로 즉시 트리거된다는 점이 명시되어 있지 않음.

### 수정 방법
- [x] 다음 문구를 캐시 무효화 전략 섹션에 추가:

> "TTL 5분은 자연 만료 시간이며, 팔로우/언팔로우/서클 변경 시에는 `@CacheEvict`가 즉시 트리거되어 다음 요청에서 최신 데이터가 반영된다. TTL은 명시적 이벤트 없이 캐시가 무한히 남는 것을 방지하는 안전장치 역할이다."

---

## 전체 캐시 무효화 매트릭스

수정 후 상태:

| 메서드 | followingIds[member] | circleIds[member] | followingIds[target] | circleIds[target] | 상태 |
|--------|---------------------|-------------------|---------------------|-------------------|------|
| follow() | ✅ 무효화 | — | — | — | 현재 OK |
| unfollow() | ✅ 무효화 | ✅ 무효화 | — | ✅ 무효화 | 현재 OK |
| updateCircleStatus() | — | ⚠️ **추가 필요** | — | ✅ 무효화 | 이슈 2 |
| block() | ⚠️ **추가 필요** | ⚠️ **추가 필요** | ⚠️ **추가 필요** | ⚠️ **추가 필요** | 이슈 1 |
| unblock() | — | — | — | — | 현재 OK (팔로우 재생성 없음) |

---

## 작업 우선순위

| 순위 | 이슈 | 이유 |
|------|------|------|
| 1 | block() 캐시 무효화 | 차단한 사용자 게시글이 피드에 노출되는 심각한 UX 문제 |
| 2 | updateCircleStatus() 무효화 보완 | CORE 피드 정합성 |
| 3 | 문서 보완 (즉시 무효화 명시) | 포트폴리오 설명 보강 |
