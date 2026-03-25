# Phase 1: 인덱스 + 트랜잭션 최적화

> 완료일: 2026-02-20

## 작업 범위

피드(Feed) + 댓글(Comments) 및 종속 엔티티 (TodayQuestion, Follow, Device)

---

## 1-1. DB 인덱스 추가

### 추가된 인덱스

| 테이블 | 인덱스명 | 컬럼 | 용도 |
|--------|----------|------|------|
| today_question | `idx_tq_member_order_date` | (member_id, question_order, selected_date) | 피드 질문 조회 |
| post | `idx_post_member_status` | (member_id, status) | 피드 게시글 필터링 |
| post | `idx_post_status_created` | (status, created_at) | 피드 정렬 |
| comment | `idx_comment_post_depth_id` | (post_id, depth, id) | 댓글 목록 조회 |
| comment | `idx_comment_parent_id` | (parent_id, id) | 대댓글 조회 |
| follow | `idx_follow_follower_circle` | (follower_id, is_circle) | 서클 피드 필터링 |
| device | `idx_device_member_active` | (member_id, is_active) | FCM 토큰 조회 |

### EXPLAIN 결과 (Before → After)

| 쿼리 | Before | After |
|------|--------|-------|
| TodayQuestion 조회 | rows:212, filtered:1.11% | **rows:1, filtered:100%** |
| Post 피드 조회 | rows:20, filtered:50% | **rows:100, filtered:100%** |
| Comment 댓글 조회 | rows:3, filtered:10% | **rows:1, filtered:100%** |
| Comment 대댓글 조회 | rows:3, filtered:100% | **rows:1, filtered:100%** |
| Follow 서클 조회 | rows:20, filtered:50% | **rows:2, filtered:100%** |

### 변경 파일

- `TodayQuestion.java` — `@Table(indexes = {...})` 추가
- `Post.java` — `@Table(indexes = {...})` 추가
- `Comment.java` — `@Table(indexes = {...})` 추가
- `Follow.java` — `@Table(indexes = {...})` 추가
- `Device.java` — `@Table(indexes = {...})` 추가

---

## 1-2. @Transactional(readOnly=true) 보완

### 변경 내용

`PostQueryServiceImpl`에 클래스 레벨 `@Transactional(readOnly = true)` 추가.

**효과:**
- Hibernate dirty checking 비활성화 → 메모리/CPU 절약
- MySQL read replica 라우팅 가능 (향후)
- 불필요한 flush 방지

### 기존 현황

| 서비스 | 적용 여부 |
|--------|----------|
| PostQueryServiceImpl | ❌ → ✅ 추가 |
| CommentQueryServiceImpl | ✅ 이미 적용됨 |

### 변경 파일

- `PostQueryServiceImpl.java` — `@Transactional(readOnly = true)` 추가

---

## 1-3. Hibernate Batch Fetch Size 설정

### 변경 내용

`application-local.yml`에 `default_batch_fetch_size: 100` 추가.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

**효과:**
- N+1 문제에서 lazy loading 시 IN 절로 배치 조회
- 예: 10건의 Post → 10번 Member 조회 → 1번 IN(id1, id2, ..., id10) 조회

---

## 1-4. HikariCP 커넥션 풀 튜닝

### 변경 내용

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      idle-timeout: 30000
      connection-timeout: 5000
      max-lifetime: 600000
```

| 설정 | 기본값 | 변경값 | 이유 |
|------|--------|--------|------|
| maximum-pool-size | 10 | 20 | 100 VUs 동시 요청 대응 |
| minimum-idle | 10 | 10 | 유휴 커넥션 유지 |
| idle-timeout | 600s | 30s | 불필요한 커넥션 빠른 반환 |
| connection-timeout | 30s | 5s | 빠른 실패 감지 |
| max-lifetime | 1800s | 600s | 커넥션 주기적 갱신 |

---

## 부하 테스트 결과 (Before → After)

> 테스트 환경: 로컬 (MacOS), MySQL 918MB, 100 VUs, 3분 30초

### 시나리오별 비교

| 시나리오 | Baseline p95 | Phase 1 p95 | 개선율 |
|----------|-------------|-------------|--------|
| Feed | 489ms | **245ms** | **-49.9%** |
| Profile | 438ms | **130ms** | **-70.3%** |
| Notifications | 439ms | **160ms** | **-63.6%** |
| Questions | 419ms | **201ms** | **-52.0%** |
| Search | 464ms | **177ms** | **-61.9%** |

### 전체 지표 비교

| 지표 | Baseline | Phase 1 | 변화 |
|------|----------|---------|------|
| 에러율 | 0.01% | 0.02% | 유지 |
| 총 요청 | 20,269 | **22,075** | +8.9% |
| 처리량 | 86 req/s | **93 req/s** | +8.1% |
| http_req_duration p95 | 518ms | **266ms** | **-48.6%** |
| 최대 응답시간 | 2.07s | **1.69s** | -18.4% |

### 핵심 성과

- **p95 응답시간 48.6% 감소** (518ms → 266ms)
- **처리량 8.1% 증가** (86 → 93 req/s)
- 모든 Threshold 통과 (에러율 < 10%, 각 시나리오 p95 < 목표)
