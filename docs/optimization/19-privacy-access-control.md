# 프라이버시 기반 접근 제어 아키텍처

> 완료일: 2026-03-17

## 배경: 왜 이 작업이 필요했나

CoreDisc는 3단계 가시성 모델(OFFICIAL/CIRCLE/PERSONAL)을 가진 소셜 다이어리 앱이다.
피드 쿼리에는 가시성 필터링이 존재하지만, **개별 Post 접근 경로(상세 조회, 좋아요, 댓글)에는
가시성 검증이 없어 postId만 알면 CIRCLE/PERSONAL 글에 누구나 접근 가능한 상태**였다.

추가로 발견된 프라이버시 갭:
- Block 시 기존 좋아요/댓글/알림이 정리되지 않음
- Follow 알림이 동기 처리 (API 응답 지연)
- Circle 해제가 단방향만 처리됨
- `unblock()` 캐시 무효화 누락 버그

---

## 아키텍처: 3중 방어 구조

```
[캐시 무효화] → [접근 검증 게이트웨이] → [비동기 정리]
                       ↑
                 모든 Post 접근 경로
```

| 계층 | 역할 | 구현 |
|------|------|------|
| 1. 캐시 무효화 | 관계 변경 즉시 캐시 정합성 확보 | @CacheEvict + CacheManager |
| 2. 접근 검증 | 모든 개별 Post 접근에 가시성 확인 | PostVisibilityChecker |
| 3. 비동기 정리 | Block 시 기존 상호작용 정리 | 이벤트 기반 리스너 |

---

## Phase 1: PostVisibilityChecker — 접근 제어 게이트웨이

### 설계 결정

- **모든 거부는 404** (403이 아님) — Post 존재 자체를 숨김
- Block 체크: 기존 인덱스 활용 `existsByBlockerAndBlocked` 2회 (sub-ms)
- Circle 체크: 기존 `@Cacheable("circleIds")` 활용 → 캐시 HIT 시 DB 쿼리 0회
- 성능 영향: **+1~4ms per request**

### 접근 검증 로직

```
요청자 == 작성자?     → 접근 허용
Block 관계 (양방향)?  → 404
OFFICIAL 글?         → 접근 허용
CIRCLE 글?           → 요청자가 작성자의 Circle인지 확인
PERSONAL 글?         → 404 (본인만 접근 가능)
```

### 적용 대상

| API | 메서드 | 적용 위치 |
|-----|--------|----------|
| 게시글 상세 조회 | `PostQueryServiceImpl.findPostDetail()` | Post 로드 직후 |
| 좋아요 생성 | `PostLikeCommandServiceImpl.createLike()` | Post 검증 직후 |
| 좋아요 삭제 | `PostLikeCommandServiceImpl.deleteLike()` | Post 검증 직후 |
| 댓글 생성 | `CommentCommandServiceImpl.createComment()` | Post/Member 로드 직후 |
| 대댓글 생성 | `CommentCommandServiceImpl.createReply()` | parentComment 로드 직후 |

### 변경 파일

| 파일 | 작업 |
|------|------|
| `PostVisibilityChecker.java` (신규) | 접근 검증 로직 |
| `PostQueryServiceImpl.java` | `findPostDetail()`에 validateAccess 추가 |
| `PostLikeCommandServiceImpl.java` | `createLike()`, `deleteLike()`에 validateAccess 추가 |
| `CommentCommandServiceImpl.java` | `createComment()`, `createReply()`에 validateAccess 추가 |

---

## Phase 2: 관계 변경 이벤트 시스템

### Before (동기 + 분산된 부수효과)

```
[Follow API] → [DB 저장] → [알림 생성 (DB)] → [디바이스 조회 (DB)] → [FCM 전송 (외부 I/O)] → [응답]
                                    ↑ 트랜잭션 내 동기 실행, 100~500ms 지연
```

### After (비동기 이벤트 기반)

```
[Follow API] → [DB 저장] → [FollowedEvent 발행] → [응답]
                                  ↓ (AFTER_COMMIT, 별도 스레드)
                           [FollowedEventListener]
                                  ↓
                           [알림 생성 + FCM 전송]
```

### 이벤트 클래스 체계

```
RelationshipEvent (abstract)
├── FollowedEvent       — actorNickname 포함
├── UnfollowedEvent
├── BlockedEvent
└── CircleChangedEvent  — isCircle (boolean) 포함
```

기존 `NotificationEvent`, `PostCountEvent`와 동일 패턴 (private constructor + static factory).

### Follow 알림 비동기 전환

| 항목 | Before | After |
|------|--------|-------|
| Follow API 응답 시간 | DB + 알림 + FCM (100~500ms) | DB + 이벤트 발행만 |
| FollowCommandService 의존성 | 6개 (MemberRepo, FollowRepo, BlockRepo, DeviceRepo, NotificationService, FcmService) | **4개** (MemberRepo, FollowRepo, BlockRepo, EventPublisher) |
| 실패 전파 | FCM 실패 시 API 에러 위험 | 격리됨 (리스너 내 try-catch) |

### unblock() 캐시 무효화 버그 수정

```java
// Before: unblock() — @CacheEvict 없음 → 차단 해제 후에도 stale 캐시 유지
public void unblock(Member member, Long targetId) { ... }

// After: block()과 동일한 4개 캐시 eviction 추가
@Caching(evict = {
    @CacheEvict(value = "followingIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "followingIds", key = "#targetId"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public void unblock(Member member, Long targetId) { ... }
```

### 변경 파일

| 파일 | 작업 |
|------|------|
| `RelationshipEvent.java` (신규) | 추상 베이스 이벤트 |
| `FollowedEvent.java` (신규) | 팔로우 이벤트 |
| `UnfollowedEvent.java` (신규) | 언팔로우 이벤트 |
| `BlockedEvent.java` (신규) | 차단 이벤트 |
| `CircleChangedEvent.java` (신규) | Circle 변경 이벤트 |
| `FollowedEventListener.java` (신규) | Follow 비동기 알림+FCM |
| `FollowCommandServiceImpl.java` | 동기 알림 제거 → 3개 이벤트 발행 |
| `BlockCommandServiceImpl.java` | BlockedEvent 발행 + unblock CacheEvict 수정 |

---

## Phase 3: Block 상호작용 정리

### 문제

Block 후에도 차단된 유저의 좋아요/댓글/알림이 그대로 남아있었음.

### 해결: 이벤트 기반 비동기 양방향 정리

```
[Block API] → [팔로우 삭제 + Block 저장] → [BlockedEvent 발행] → [응답]
                                                  ↓ (AFTER_COMMIT)
                              ┌─────────────────────────────────────┐
                              │ InteractionCleanupListener          │
                              │ 1. 양방향 좋아요 삭제 + likeCount 보정    │
                              │ 2. 양방향 댓글 soft delete + commentCount 보정 │
                              ├─────────────────────────────────────┤
                              │ NotificationCleanupListener         │
                              │ 3. 양방향 알림 삭제                      │
                              └─────────────────────────────────────┘
```

### 카운트 정합성 보장 방식

1. 삭제 전 조회로 postId별 count 집계
2. 벌크 삭제/soft delete 실행
3. postId별 count 벌크 보정 (`CASE WHEN count >= amount THEN count - amount ELSE 0 END`)

### 도메인 레이어 확장

| Repository | 추가 메서드 | 용도 |
|------------|-----------|------|
| PostLikeRepository | `findAllByMemberIdAndPostMemberId`, `deleteAllByMemberIdAndPostMemberId` | 좋아요 조회/벌크삭제 |
| CommentRepository | `findAllActiveByMemberIdAndPostMemberId`, `softDeleteAllByMemberIdAndPostMemberId` | 댓글 조회/soft delete |
| NotificationRepository | `deleteAllBySenderIdAndReceiverId` | 알림 벌크삭제 |
| PostRepository | `decrementLikeCountByAmount`, `decrementCommentCountByAmount` | 카운트 벌크 보정 |

---

## Phase 4: Circle 양방향 해제

### 문제

A가 B를 Circle에서 해제해도, B→A 방향의 Circle은 그대로 유지 → B 피드에 A의 CIRCLE 글이 노출.

### 해결

```java
// CircleChangedEventListener (AFTER_COMMIT, 별도 트랜잭션)
if (!event.isCircle()) {  // Circle 해제인 경우만
    Follow reverseFollow = followRepository.findByFollowerAndFollowing(target, actor);
    if (reverseFollow != null && reverseFollow.isCircle()) {
        reverseFollow.updateCircle(false);
        // async 컨텍스트이므로 @CacheEvict 불가 → CacheManager 수동 evict
        cacheManager.getCache("circleIds").evict(targetId);
        cacheManager.getCache("circleIds").evict(actorId);
    }
}
```

---

## 전체 변경 요약

### 신규 파일 (10개)

| # | 파일 | 목적 |
|---|------|------|
| 1 | `PostVisibilityChecker.java` | 접근 제어 게이트웨이 |
| 2 | `RelationshipEvent.java` | 추상 베이스 이벤트 |
| 3 | `FollowedEvent.java` | 팔로우 이벤트 |
| 4 | `UnfollowedEvent.java` | 언팔로우 이벤트 |
| 5 | `BlockedEvent.java` | 차단 이벤트 |
| 6 | `CircleChangedEvent.java` | Circle 변경 이벤트 |
| 7 | `FollowedEventListener.java` | Follow 비동기 알림 |
| 8 | `InteractionCleanupListener.java` | Block 좋아요/댓글 정리 |
| 9 | `NotificationCleanupListener.java` | Block 알림 정리 |
| 10 | `CircleChangedEventListener.java` | Circle 양방향 해제 |

### 수정 파일 (15개)

| 계층 | 파일 | 변경 |
|------|------|------|
| Service | PostQueryServiceImpl | validateAccess 추가 |
| Service | PostLikeCommandServiceImpl | validateAccess 추가 |
| Service | CommentCommandServiceImpl | validateAccess 추가 |
| Service | FollowCommandServiceImpl | 동기알림→이벤트, 의존성 6→4 |
| Service | BlockCommandServiceImpl | 이벤트 발행, unblock 캐시 버그 수정 |
| Domain | PostLikeRepository | bulk 조회/삭제 메서드 |
| Domain | CommentRepository | bulk 조회/soft delete 메서드 |
| Domain | NotificationRepository | bulk 삭제 메서드 |
| Domain | PostRepository | count 벌크 보정 메서드 |
| Infra | JpaPostLikeRepository | JPQL 쿼리 추가 |
| Infra | PostLikeRepositoryAdaptor | 어댑터 구현 |
| Infra | JpaCommentRepository | JPQL 쿼리 추가 |
| Infra | CommentRepositoryAdaptor | 어댑터 구현 |
| Infra | JpaNotificationRepository | JPQL 쿼리 추가 |
| Infra | NotificationRepositoryAdaptor + JpaPostRepository + PostRepositoryAdaptor | 어댑터 구현 + 벌크 보정 |

---

## 검증

| 항목 | 결과 |
|------|------|
| `./gradlew compileJava` | 성공 |
| 기존 테스트 | 6/7 통과 (1건 기존 DB 연결 문제, 무관) |

### 예상 성능 영향

| 항목 | 예상 영향 |
|------|----------|
| Post 상세 조회 | +1~4ms (Block 체크 + circleIds 캐시) |
| 좋아요/댓글 API | +1~4ms (동일) |
| Follow API | **-100~500ms** (동기 FCM → 비동기 전환) |
| Block API | +0ms (정리는 비동기) |
