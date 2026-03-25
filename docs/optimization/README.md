# CoreDisc 성능 최적화 기록

부하 테스트 기반으로 진행한 성능 최적화 과정을 기록합니다.

## 문서 목록

| 문서 | 설명 | 상태 |
|------|------|------|
| [01-sql-logging-query-analysis.md](./01-sql-logging-query-analysis.md) | p6spy 설정 & API별 쿼리 수 측정 | 완료 |
| [02-explain-analysis.md](./02-explain-analysis.md) | EXPLAIN 분석 & 인덱스 계획 | 완료 |
| [03-phase1-indexes-transactions.md](./03-phase1-indexes-transactions.md) | Phase 1: 인덱스 + 트랜잭션 최적화 | 완료 |
| [04-phase2-n-plus-one.md](./04-phase2-n-plus-one.md) | Phase 2: N+1 쿼리 최적화 | 완료 |
| [05-phase3-async-events.md](./05-phase3-async-events.md) | Phase 3: 비동기 이벤트 전환 | 완료 |
| [06-phase4-caching.md](./06-phase4-caching.md) | Phase 4: 캐싱 레이어 (Caffeine) | 완료 |
| [07-phase5-batch.md](./07-phase5-batch.md) | Phase 5: 배치 최적화 | 완료 |
| [08-phase6-final.md](./08-phase6-final.md) | Phase 6: 최종 검증 및 성과 정리 | 완료 |

## 성능 추이

> 테스트 환경: 로컬 (MacOS), MySQL 918MB, 100 VUs, 3분 30초

### p95 응답시간 (ms)

| 시나리오 | Baseline | Phase 1 | Phase 2 | Phase 3 | Phase 4 | 총 개선율 |
|----------|----------|---------|---------|---------|---------|-----------|
| Feed | 489ms | 245ms | 290ms | 262ms | **246ms** | **-49.7%** |
| Profile | 438ms | 130ms | 185ms | 141ms | **127ms** | **-71.0%** |
| Notifications | 439ms | 160ms | 187ms | 154ms | **167ms** | **-61.9%** |
| Questions | 419ms | 201ms | 262ms | 190ms | **197ms** | **-53.0%** |
| Search | 464ms | 177ms | 219ms | 159ms | **188ms** | **-59.5%** |

### 쿼리 수 개선

| API | Baseline | Phase 2 | Phase 4 | 총 감소율 |
|-----|----------|---------|---------|-----------|
| Feed ALL | 16 | 7 | **7 (Cache HIT)** | -56% |
| Feed CORE | 17 | 7 | **7 (Cache HIT)** | -59% |
| 부모 댓글 | ~30+ | **4** | 4 | -87% |
| 게시글 상세 | ~10+ | ~10+ | **~6** | **-40%** |
| 리마인더 스케줄러 (50명) | ~600 | — | **2** | **-99.7%** |

### 전체 지표

| 지표 | Baseline | Phase 1 | Phase 2 | Phase 3 | Phase 4 | 총 변화 |
|------|----------|---------|---------|---------|---------|---------|
| 에러율 | 0.01% | 0.02% | 0.01% | 0.01% | **0.03%** | 유지 |
| 총 요청 | 20,269 | 22,075 | 21,422 | 21,912 | **21,795** | +7.5% |
| 처리량 | 86 req/s | 93 req/s | 93 req/s | 94 req/s | **94 req/s** | +9.3% |
| http_req_duration p95 | 518ms | 266ms | 317ms | 270ms | **296ms** | **-42.9%** |
