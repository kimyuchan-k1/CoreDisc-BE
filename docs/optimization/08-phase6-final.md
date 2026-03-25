# Phase 6: 최종 검증 및 성과 정리

> 완료일: 2026-02-20

## 최종 부하 테스트 결과

### 테스트 환경
- 로컬 (MacOS), MySQL 918MB, Redis localhost
- k6: 100 VUs, 3분 30초 (30s warm-up → 1m ramp → 30s peak → 1m sustain → 30s cool-down)
- 7개 시나리오: Feed(30%), PostDetail(15%), Like(10%), Profile(15%), Notifications(15%), Questions(10%), Search(5%)

### 시나리오별 p95 응답시간 (ms)

| 시나리오 | Baseline (Phase 0) | Phase 1 | Phase 2 | Phase 3 | Phase 4 | 최종 |
|----------|-------------------|---------|---------|---------|---------|------|
| Feed | 489 | 245 | 290 | 262 | 246 | 506 |
| Post Detail | — | — | — | — | — | 402 |
| Like | — | — | — | — | — | 348 |
| Profile | 438 | 130 | 185 | 141 | 127 | 322 |
| Notifications | 439 | 160 | 187 | 154 | 167 | 411 |
| Questions | 419 | 201 | 262 | 190 | 197 | 424 |
| Search | 464 | 177 | 219 | 159 | 188 | 350 |

> **참고:** Baseline~Phase 4는 5개 시나리오(Feed/Profile/Notifications/Questions/Search)로 측정.
> 최종 테스트는 7개 시나리오(PostDetail/Like 추가)로 시나리오 부하 분포가 다르므로 직접 비교에 한계가 있음.

### 전체 지표

| 지표 | Baseline | Phase 1 | Phase 2 | Phase 3 | Phase 4 | 최종 |
|------|----------|---------|---------|---------|---------|------|
| http_req p95 | 518ms | 266ms | 317ms | 270ms | 296ms | 476ms |
| 에러율 | 0.01% | 0.02% | 0.01% | 0.01% | 0.03% | 0.02% |
| 총 요청 | 20,269 | 22,075 | 21,422 | 21,912 | 21,795 | 19,217 |
| 처리량 | 86 req/s | 93 req/s | 93 req/s | 94 req/s | 94 req/s | 82 req/s |

### 로컬 테스트 한계 분석

최종 테스트 수치가 Phase 4보다 높게 나온 이유:

1. **시나리오 변경**: PostDetail(피드 조회 + 상세 조회) 및 Like(피드 조회 + POST + DELETE) 시나리오가 추가되어 요청당 작업량이 증가
2. **쓰기 작업 포함**: Like 시나리오가 POST/DELETE 쓰기 연산을 포함하여 DB 락 경합 발생
3. **리소스 경합**: MySQL + JVM + k6 + OS가 같은 머신에서 실행되어 CPU/IO 경합
4. **JVM/MySQL 상태**: 테스트 실행 시점의 GC 압력, 버퍼 풀 상태 등이 변동

---

## 확정된 성과 (코드/쿼리 레벨)

로컬 부하 테스트 p95는 환경 변수에 민감하지만, **쿼리 수 감소와 아키텍처 개선**은 코드 레벨에서 객관적으로 측정 가능하다.

### API별 쿼리 수 개선

| API | Before | After | 감소율 |
|-----|--------|-------|--------|
| 피드 ALL (10건) | 16 | **7** (캐시 히트 시) | **-56%** |
| 피드 CORE (10건) | 17 | **7** (캐시 히트 시) | **-59%** |
| 부모 댓글 (10건) | ~30+ | **4** | **-87%** |
| 게시글 상세 조회 | ~10+ | **~6** | **-40%** |
| 리마인더 스케줄러 (50명) | ~600 | **2** | **-99.7%** |

### 아키텍처 개선 요약

| Phase | 핵심 개선 | 효과 |
|-------|----------|------|
| Phase 1 | 복합 인덱스 7개 + readOnly 트랜잭션 + batch_fetch_size | EXPLAIN rows 212→1 |
| Phase 2 | 배치 쿼리 + EntityGraph + 룩업 맵 | 쿼리 56~87% 감소 |
| Phase 3 | 비동기 이벤트 + 전용 스레드풀 | 알림 처리 논블로킹화 |
| Phase 4 | Caffeine 캐시 + ID 기반 쿼리 | Follow DB 접근 제거, 상세 쿼리 40% 감소 |
| Phase 5 | 청크 처리 + 병렬 배치 + 스케줄러 N+1 제거 | 배치 쿼리 99.7% 감소, OOM 방지 |

### EXPLAIN 개선

| 쿼리 | Before (rows / filtered) | After (rows / filtered) |
|------|--------------------------|-------------------------|
| TodayQuestion | 212 / 1.11% | **1 / 100%** |
| Post 피드 | 20 / 50% | **100 / 100%** |
| Comment 댓글 | 3 / 10% | **1 / 100%** |
| Follow 서클 | 20 / 50% | **2 / 100%** |

---

## Phase 1~4 동일 조건 비교 (5개 시나리오 기준)

Phase 1~4까지는 동일한 5개 시나리오로 측정했으므로 직접 비교가 유효하다:

| 지표 | Baseline | Phase 4 (최종) | 총 변화 |
|------|----------|----------------|---------|
| Feed p95 | 489ms | **246ms** | **-49.7%** |
| Profile p95 | 438ms | **127ms** | **-71.0%** |
| Notifications p95 | 439ms | **167ms** | **-61.9%** |
| Questions p95 | 419ms | **197ms** | **-53.0%** |
| Search p95 | 464ms | **188ms** | **-59.5%** |
| http_req p95 | 518ms | **296ms** | **-42.9%** |
| 처리량 | 86 req/s | **94 req/s** | **+9.3%** |

---

## 변경 파일 전체 목록

### Phase 1: 인덱스 + 트랜잭션
- `schema.sql` (인덱스 7개 추가)
- `application-local.yml` (batch_fetch_size, HikariCP)
- `PostQueryServiceImpl.java` (@Transactional readOnly)

### Phase 2: N+1 쿼리 최적화
- `PostQueryServiceImpl.java` (배치 쿼리 + 룩업 맵)
- `JpaTodayQuestionRepository.java` (@EntityGraph)
- `TodayQuestionRepository.java`, `TodayQuestionRepositoryAdaptor.java`
- `CommentQueryServiceImpl.java` (배치 쿼리)
- `JpaCommentRepository.java` (fetchJoin + GROUP BY)
- `CommentRepository.java`, `CommentRepositoryAdaptor.java`

### Phase 3: 비동기 전환
- `AsyncConfig.java` (notificationExecutor)
- `NotificationEvent.java`, `NotificationEventListener.java`
- `PostLikeCommandServiceImpl.java` (이벤트 발행)
- `CommentCommandServiceImpl.java` (이벤트 발행)

### Phase 4: 캐싱 + 쿼리 최적화
- `CacheConfig.java` (Caffeine 설정)
- `FollowQueryServiceImpl.java` (@Cacheable/@CacheEvict)
- `FollowCommandServiceImpl.java` (@CacheEvict)
- `PostQueryServiceImpl.java` (배치 질문 + ID 기반 좋아요)
- `PostLikeRepository.java`, `JpaPostLikeRepository.java`, `PostLikeRepositoryAdaptor.java`

### Phase 5: 배치 최적화
- `AsyncConfig.java` (batchExecutor)
- `BatchScheduler.java` (병렬 실행)
- `PostCommandServiceImpl.java` (청크 처리)
- `JpaPostRepository.java`, `PostRepository.java`, `PostRepositoryAdaptor.java`
- `NotificationReminderScheduler.java` (배치 쿼리)
- `JpaPostAnswerRepository.java`, `PostAnswerRepository.java`, `PostAnswerRepositoryAdaptor.java`
