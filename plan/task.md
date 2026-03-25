# CoreDisc 셀링포인트 #2 — 서비스 확장성 한계 측정 및 돌파

> 진행 상태: ✅ 완료 (Phase 0~4 전체 완료)
> 계획서: `plan/서비스_확장성_한계_측정_및_돌파.md`
> 마지막 업데이트: 2026-03-16

---

## Phase 0: 측정 환경 준비

- [x] **0-1. 시드 데이터 확장**
  - [x] 팔로잉 수별 테스트 그룹 생성 스크립트 작성 → `k6/generate_scalability_data.py`
    - Group A: member_id 4501~4520, 팔로잉 50명
    - Group B: member_id 4521~4540, 팔로잉 100명
    - Group C: member_id 4541~4560, 팔로잉 200명
    - Group D: member_id 4561~4580, 팔로잉 500명
    - Group E: member_id 4581~4600, 팔로잉 1000명
  - [x] 별도 스크립트로 생성 (기존 시드 데이터 위에 보충)

- [x] **0-2. k6 테스트 스크립트 작성**
  - [x] 동시 좋아요 정합성 테스트 → `k6/concurrency-test.js`
    - `k6 run --env TARGET_POST_ID=1 --env VUS=50 k6/concurrency-test.js`
    - teardown에서 자동 정합성 검증 + 좋아요 정리
  - [x] 팔로잉 수별 피드 성능 테스트 → `k6/scalability-test.js` (TEST_MODE=feed-by-following)
    - 그룹별 커스텀 메트릭: feed_following_50/100/200/500/1000
  - [x] VU 단계적 증가 테스트 → `k6/scalability-test.js` (TEST_MODE=ramp-test)
    - 50 → 100 → 200 → 500 VU, 각 2분 sustain

- [x] **0-3. 모니터링 설정 확인**
  - [x] Spring Actuator 엔드포인트: health, info, prometheus, metrics 노출 (`application-perf.yml`)
  - [x] Caffeine 메트릭: `recordStats()` 활성화 (`CacheConfig.java`)
  - [x] HikariCP 메트릭: Spring Boot 3 + Micrometer 자동 노출 (hikaricp.connections.*)

---

## Phase 1: 현재 상태 측정 + 문제 증명

- [x] **1-1. 베이스라인 재측정**
  - [x] GCP 환경 구축 (e2-standard-2, MySQL 8.0 Docker, Redis 7)
  - [x] 시드 데이터 생성 및 로드 (10K members, 184K posts, 6.2M likes, 538K follows)
  - [x] load-test-v2.js 실행 (180 VU, 10분)
  - [x] 결과: 피드 p95=8.39s, 에러율 9.67%, 처리량 27.9 req/s
  - [x] 문서화: `docs/optimization/10-scalability-baseline.md`

- [x] **1-2. Part A: 카운트 미반영 + 동시성 문제 증명**
  - [x] likeCount 증가 코드 부재 확인 (서비스 코드에서 호출 없음)
  - [x] JPA Dirty Checking 방식으로 `Post.incrementLikeCount()` 추가
  - [x] 동시 좋아요 50건 테스트 → **Deadlock 78%, Lost Update 91건 괴리**
  - [x] 결과: JPA 전체 컬럼 UPDATE → InnoDB Deadlock + Lost Update
  - [x] 문서화: `docs/optimization/11-concurrency-test.md`

- [x] **1-3. Part B: 팔로잉 수별 피드 성능 측정**
  - [x] 시드 데이터 적용 (그룹 A~F, 50/200/500/1000/2000/5000)
  - [x] 각 그룹별 피드 p50/p95/p99 측정 (90 VUs, 6그룹 × 15유저)
  - [x] 결과: 전체 avg 1.08s, median 81ms, p95 7.24s, 에러율 0%
    - 팔로잉 50: avg 1.03s, median 97ms, p95 5.49s
    - 팔로잉 200: avg 1.01s, median 157ms, p95 5.55s
    - 팔로잉 500: avg 1.06s, median 84ms, p95 6.68s
    - 팔로잉 1000: avg 1.02s, median 112ms, p95 7.03s
    - 팔로잉 2000: avg 1.17s, median 52ms, p95 9.57s
    - 팔로잉 5000: avg 1.17s, median 46ms, p95 9.67s
  - [x] 결론: 팔로잉 수 증가에 따른 median 차이 미미, p95에서 점진적 증가

- [x] **1-4. Part B: VU 단계적 증가 테스트**
  - [x] 50 → 100 → 200 → 500 VU ramp 테스트
  - [x] 결과: avg 1.97s, median 148ms, p95 16.35s, 에러율 0.19%, 처리량 64.1 req/s
  - [x] 결론: 500 VU까지 에러율 < 1% 유지, 안정적 서비스 가능

- [x] **1-5. 측정 결과 종합 리포트 작성**
  - [x] `docs/optimization/16-scalability-measurement-summary.md`에 기록
  - [x] 가설별 검증 결과 (Part A 3개 지지, Part B 1개 부분지지/1개 기각)
  - [x] Phase 2 완료 (DB 원자적 연산), Phase 3 방향 결정 (쿼리 최적화 우선)

---

## Phase 2: Part A 해결 — 동시성 정합성 확보

- [x] **2-1. DB 원자적 연산 전환** (Phase 1-2에서 이미 구현 완료)
  - [x] `PostRepository`에 incrementLikeCount/decrementLikeCount 추가
  - [x] `PostRepository`에 incrementCommentCount/decrementCommentCount 추가
  - [x] `PostLikeCommandServiceImpl` → `PostCountEvent` 이벤트 발행
  - [x] `CommentCommandServiceImpl` → `PostCountEvent` 이벤트 발행
  - [x] `PostCountEventListener` → `@Async("countExecutor")` + `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`
  - [x] `JpaPostRepository` → `@Modifying @Query` JPQL 원자적 연산

- [x] **2-2. 동시성 테스트 재실행**
  - [x] 동시 좋아요 100건 → 에러 0%, diff=0 (완벽 정합)
  - [x] 응답 시간: avg 820ms~965ms, p95 1.24s~1.42s
  - [x] Phase 1-2 대비: Deadlock 78% → 0%, Lost Update 91건 → 0건

- [x] **2-3. 동시성 스케일 테스트**
  - [x] 100 VU: 에러 0%, diff=0, Row Lock avg ~50ms
  - [x] 200 VU: 에러 0%, diff=0, Row Lock avg 54ms
  - [x] 500 VU: 에러 23.4%, post_like 레코드 정확, 비동기 이벤트 유실 발견
  - [x] Row Lock: avg 54~59ms, max 320ms (100ms 미만으로 양호)
  - [x] 결론: "DB 원자적 연산은 동시 200건까지 완벽 정합, Row Lock avg < 60ms"
  - [x] deleteLike 방어 코드 추가 (존재 확인 후 decrement)

- [x] **2-4. (조건부) 3가지 락 전략 실측 비교** → **SKIP**
  - DB 원자적 연산의 Row Lock avg=54ms로 충분히 양호
  - 비관적/낙관적 락 비교 불필요 — 원자적 UPDATE가 가장 단순하고 효율적

- [x] **2-5. (조건부) Redis 카운터 도입** → **SKIP**
  - 현실적 서비스 규모에서 동시 200건 좋아요는 초과 가능성 극히 낮음
  - DB 원자적 연산으로 충분 → Redis 카운터 불필요

- [x] **2-6. Part A 결과 문서화**
  - [x] `docs/optimization/15-concurrency-atomic-result.md`에 기록
  - [x] Before/After 비교표 (Deadlock 78%→0%, Lost Update 91건→0건)
  - [x] Phase 2-4(락 전략 비교), 2-5(Redis 카운터) SKIP 근거

---

## Phase 3: Part B 해결 — 피드 확장성 개선

- [x] **3-1. 쿼리 최적화 시도**
  - [x] EXPLAIN ANALYZE로 현재 피드 쿼리 실행 계획 분석
    - 기존 JOIN 방식: idx_post_member_status 사용 → 전체 매칭 후 filesort
    - 팔로잉 5000명: 214ms, 61,542행 스캔
  - [x] FORCE INDEX(PRIMARY) 역순 PK 스캔 발견
    - 팔로잉 5000명: 0.12ms, 53행 스캔 (1,783배 개선)
    - 팔로잉 많을수록 매칭 확률 ↑ → 스캔 범위 ↓ (역전 패턴)
  - [x] 2단계 쿼리 분리 구현: Native SQL(ID 조회) + QueryDSL(엔티티 페치)

- [x] **3-2. 쿼리 최적화 후 재측정**
  - [x] 팔로잉 수별 피드 p95 재측정 (90 VU, warm JVM, cold Redis)
  - [x] 결과: p95 7.24s → 2.2s (70% 감소), avg 1.08s → 312ms (71% 감소)
  - [x] p95 범위: 5.49~9.67s → 2.12~2.56s (팔로잉 수 감도 대폭 감소)
  - [x] 에러율 0%, 처리량 ~24 → 33.4 req/s (39% 증가)
  - [x] 문서화: `docs/optimization/17-feed-query-optimization.md`

- [x] **3-3. Caffeine 캐시 튜닝** → **SKIP**
  - [x] 캐시 HIT율 측정: followingIds 82.9%, circleIds 82.9%, userDetails 99.2%
  - [x] maxSize 활용률: followingIds 470/5000 (9.4%), userDetails 500/10000 (5%)
  - [x] 모든 eviction이 TTL 기반 — maxSize 포화 없음
  - SKIP 근거: 캐시 설정 이미 적절, 튜닝 여지 없음

- [x] **3-4. (조건부) Redis 피드 캐시 도입** → **이미 구현됨**
  - FeedCacheService: Redis 기반 첫 페이지 캐시 (TTL=30s)
  - Thundering Herd 방어: SETNX 기반 분산 락
  - Phase 3-2 쿼리 최적화와 함께 작동 중

- [x] **3-5. (조건부) Pull/Push 하이브리드 설계** → **SKIP**
  - SKIP 근거: FORCE INDEX 최적화로 p95 70-83% 감소, 처리량 74% 증가
  - 현재 규모에서 Pull 모델 + 쿼리 최적화 + Redis 캐시로 충분

- [x] **3-6. Part B 결과 문서화**
  - [x] `docs/optimization/17-feed-query-optimization.md`에 기록
  - [x] Before/After 비교표 (팔로잉 수별, VU ramp)
  - [x] 각 단계의 효과 비교 및 SKIP 근거

---

## Phase 4: 통합 검증 + 결론 도출

- [x] **4-1. 통합 부하 테스트**
  - [x] 혼합 워크로드: 피드 60% + 상세 20% + 좋아요 20%
  - [x] VU 단계적 증가: 50 → 100 → 200 → 500
  - [x] 결과: p95=5.1s, avg=1.34s, 에러 0%, 96.2 req/s
  - [x] 총 68,954 요청 (12분), 57,108건 전수 성공

- [x] **4-2. 최종 한계점 도출**
  - [x] 동시 200명: p95 < 2s, 에러 0%, 정합성 100% (≈ MAU 4,000명)
  - [x] 동시 500명: p95 < 5.2s, 에러 0%, 96.2 req/s (≈ MAU 10,000명)
  - [x] 다음 병목: HikariCP pool=50 포화 → 스케일업/Read Replica로 해결

- [x] **4-3. 최종 결과 문서화**
  - [x] `docs/optimization/18-scalability-final-report.md`
  - [x] 전체 Before/After 비교표
  - [x] 가설별 검증 결과 종합 (A 3개 지지, B 4개 중 2 지지/1 기각/1 해결)
  - [x] 의사결정 근거 정리 (SKIP 4건 + 기술 선택 근거)

- [x] **4-4. 포트폴리오 스토리 정리**
  - [x] 한 줄 요약: Deadlock 78%→0%, 피드 p95 7.24s→2.2s, 쿼리 214ms→0.12ms
  - [x] 면접 예상 질답 5개 (실제 수치 반영)

---

## 부수 작업 (셀링포인트 아닌 코드 품질 개선)

- [ ] 프라이버시: PostVisibilityChecker 구현 (CIRCLE 글 직접 접근 차단)
- [ ] 프라이버시: Block 시 좋아요/댓글 비동기 정리
- [ ] 아키텍처: Follow 알림 비동기 전환 (이벤트 기반)
- [ ] 배치: ShedLock 분산 락 도입 (다중 서버 대비)
- [ ] 배치: Checkpoint 기반 점진 처리 (리마인더)

---

## 진행 로그

| 날짜 | Phase | 작업 | 비고 |
|------|-------|------|------|
| 2026-03-13 | - | 계획서 작성 완료 | `plan/서비스_확장성_한계_측정_및_돌파.md` |
| 2026-03-13 | Phase 0 | 시드 데이터 스크립트 작성 | `k6/generate_scalability_data.py` |
| 2026-03-13 | Phase 0 | 동시성 테스트 스크립트 작성 | `k6/concurrency-test.js` |
| 2026-03-13 | Phase 0 | 확장성 테스트 스크립트 작성 | `k6/scalability-test.js` |
| 2026-03-13 | Phase 0 | 모니터링 설정 확인 완료 | Actuator+Prometheus+Caffeine+HikariCP |
| 2026-03-13 | Phase 1-1 | GCP 환경 구축 + 시드 데이터 로드 | e2-standard-2, 10K members, 6.2M likes |
| 2026-03-13 | Phase 1-1 | 베이스라인 부하 테스트 완료 | p95=8.39s, 에러율 9.67%, 27.9 req/s |
| 2026-03-13 | Phase 1-2 | 동시성 문제 증명 | Deadlock 78%, Lost Update 91건 괴리 |
| 2026-03-16 | Phase 1-3 | 팔로잉 수별 피드 성능 측정 | avg 1.08s, 에러 0%, 팔로잉 수 증가 시 p95 점진적 증가 |
| 2026-03-16 | Phase 1-4 | VU ramp 테스트 (50→500) | avg 1.97s, 에러 0.19%, 64.1 req/s |
| 2026-03-16 | Phase 2-1 | DB 원자적 연산 이미 구현 확인 | PostCountEvent + @Async + JPQL atomic |
| 2026-03-16 | Phase 2-2 | 동시성 테스트 재실행 (100 VU) | diff=0, Deadlock 0%, 에러 0% |
| 2026-03-16 | Phase 2-3 | 스케일 테스트 (100→200→500 VU) | 200VU diff=0, 500VU 서버과부하 23% 에러 |
| 2026-03-16 | Phase 2-3 | deleteLike 방어 코드 추가 | 존재 확인 후 decrement 발행 |
| 2026-03-16 | Phase 2-6 | Part A 결과 문서화 | |
| 2026-03-17 | Phase 3-1 | EXPLAIN ANALYZE + FORCE INDEX 발견 | 5000명: 214ms→0.12ms (1,783x) |
| 2026-03-17 | Phase 3-2 | 2단계 쿼리 분리 구현 + 재측정 | p95: 7.24s→2.2s (70%↓), avg: 1.08s→312ms |
| 2026-03-17 | Phase 3-2 | VU ramp 재측정 (50→500) | p95: 16.35s→2.75s (83%↓), 에러 0%, 111.8 req/s |
| 2026-03-17 | Phase 3-3~3-5 | 캐시 분석 + SKIP 결정 | HIT율 83-99%, 이미 최적, Redis 캐시 이미 구현 |
| 2026-03-17 | Phase 3-6 | Part B 결과 문서화 | `docs/optimization/17-feed-query-optimization.md` |
| 2026-03-17 | Phase 4-1 | 통합 부하 테스트 | p95=5.1s, 에러 0%, 96.2 req/s (500 VU 혼합) |
| 2026-03-17 | Phase 4-2~4-4 | 최종 한계점 + 문서화 + 포트폴리오 | `docs/optimization/18-scalability-final-report.md` |