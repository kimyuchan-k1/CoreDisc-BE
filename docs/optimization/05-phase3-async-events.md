# Phase 3: 비동기 전환 (이벤트 기반)

> 완료일: 2026-02-20

## 작업 범위

좋아요/댓글 알림을 동기 처리에서 비동기 이벤트 기반으로 전환

---

## 3-1. AsyncConfig 확장

### 변경 내용

기존 `mailExecutor`만 있던 AsyncConfig에 `notificationExecutor` 추가.

```java
@Bean(name = "notificationExecutor")
public ThreadPoolTaskExecutor notificationExecutor() {
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("Notification-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
}
```

### 변경 파일

- `AsyncConfig.java` — `notificationExecutor` 빈 추가

---

## 3-4. 좋아요/댓글 알림 이벤트 기반 비동기 전환

### Before (동기 처리)

```
[API 요청] → [비즈니스 로직] → [알림 생성 (DB)] → [디바이스 조회 (DB)] → [FCM 전송 (외부 I/O)] → [응답]
                                        ↑ 트랜잭션 내에서 동기 실행, API 응답 지연
```

### After (비동기 이벤트)

```
[API 요청] → [비즈니스 로직] → [이벤트 발행] → [응답]
                                     ↓ (AFTER_COMMIT, 별도 스레드)
                              [알림 생성] → [FCM 전송]
```

### 구현

**1. NotificationEvent 클래스 생성**
- 정적 팩토리 메서드로 이벤트 타입별 생성: `like()`, `comment()`, `reply()`
- 필요한 최소한의 ID만 전달 (엔티티 직접 참조 방지)

**2. NotificationEventListener 생성**
- `@Async("notificationExecutor")` + `@TransactionalEventListener(phase = AFTER_COMMIT)`
- 트랜잭션 커밋 후 별도 스레드에서 알림 처리
- try-catch로 알림 실패가 비즈니스 로직에 영향 주지 않도록 격리

**3. PostLikeCommandServiceImpl 리팩토링**
- 제거: `NotificationCommandService`, `FcmService`, `DeviceRepository` 의존성
- 추가: `ApplicationEventPublisher` 의존성
- `createLike()`: 동기 알림/FCM 코드 → `eventPublisher.publishEvent()` 한 줄로 교체

**4. CommentCommandServiceImpl 리팩토링**
- 동일하게 의존성 정리 및 이벤트 발행으로 교체
- `jakarta.transaction.Transactional` → `org.springframework.transaction.annotation.Transactional` 교체 (Spring 이벤트와의 호환성)
- `createComment()`: 이벤트 발행
- `createReply()`: 이벤트 발행

### 변경 파일

| 파일 | 작업 |
|------|------|
| `application/event/NotificationEvent.java` | **새 파일** — 이벤트 클래스 |
| `application/event/NotificationEventListener.java` | **새 파일** — 비동기 이벤트 리스너 |
| `PostLikeCommandServiceImpl.java` | 리팩토링 — 의존성 3개 제거, 이벤트 발행 |
| `CommentCommandServiceImpl.java` | 리팩토링 — 의존성 4개 제거, 이벤트 발행 |

---

## 아키텍처 개선 효과

### 의존성 변화

**PostLikeCommandServiceImpl:**
- Before: PostRepository, PostLikeRepository, DeviceRepository, NotificationCommandService, FcmService (5개)
- After: PostRepository, PostLikeRepository, ApplicationEventPublisher (3개)

**CommentCommandServiceImpl:**
- Before: CommentRepository, PostRepository, MemberRepository, DeviceRepository, NotificationCommandService, FcmService (6개)
- After: CommentRepository, PostRepository, MemberRepository, ApplicationEventPublisher (4개)

### 핵심 장점

1. **응답 시간 단축**: 알림/FCM 처리가 API 응답 경로에서 완전 분리
2. **장애 격리**: FCM 장애가 좋아요/댓글 기능에 영향 주지 않음
3. **결합도 감소**: 비즈니스 서비스가 알림 인프라에 의존하지 않음
4. **트랜잭션 최적화**: DB 커넥션 점유 시간 단축 (알림 쿼리 제외)

---

## 부하 테스트 결과

> 테스트 환경: 로컬 (MacOS), MySQL 918MB, 100 VUs, 3분 30초

### 시나리오별 비교

| 시나리오 | Baseline p95 | Phase 2 p95 | Phase 3 p95 | 총 개선율 |
|----------|-------------|-------------|-------------|-----------|
| Feed | 489ms | 290ms | **262ms** | **-46.4%** |
| Profile | 438ms | 185ms | **141ms** | **-67.8%** |
| Notifications | 439ms | 187ms | **154ms** | **-64.9%** |
| Questions | 419ms | 262ms | **190ms** | **-54.7%** |
| Search | 464ms | 219ms | **159ms** | **-65.7%** |

### 전체 지표 비교

| 지표 | Baseline | Phase 2 | Phase 3 | 총 변화 |
|------|----------|---------|---------|---------|
| 에러율 | 0.01% | 0.01% | **0.01%** | 유지 |
| 총 요청 | 20,269 | 21,422 | **21,912** | +8.1% |
| 처리량 | 86 req/s | 93 req/s | **94 req/s** | +9.3% |
| http_req_duration p95 | 518ms | 317ms | **270ms** | **-47.9%** |
