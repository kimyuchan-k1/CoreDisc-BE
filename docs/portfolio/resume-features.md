# CoreDisc 백엔드 — 담당 기능 상세

> **프로젝트**: CoreDisc (자기탐색 저널링 SNS)
> **역할**: 백엔드 개발 (5인 팀)
> **기간**: 2026.01 ~ 2026.03
> **기술스택**: Java 17, Spring Boot 3.5, JPA/QueryDSL, MySQL 8.0, Redis, AWS (S3/EC2), FCM, Terraform, Prometheus/Grafana

---

## 1. 게시글 & 피드 시스템

### 1-1. 피드 조회 API (커서 기반 페이지네이션)
- **[문제]** Offset 기반 페이지네이션은 페이지가 깊어질수록 SKIP 비용이 선형 증가하여 대량 데이터에서 성능 저하
- **[해결]** 커서 기반 페이지네이션 도입 — `WHERE post.id < :cursor ORDER BY id DESC LIMIT :size`로 일정한 조회 비용 유지
- **[결과]** 데이터 양과 무관하게 일정한 응답 속도 확보

### 1-2. 게시글 가시성 제어 (OFFICIAL / CIRCLE / PERSONAL)
- **[문제]** 게시글 공개 범위(전체 공개, 친한 친구만, 나만 보기)에 따른 접근 제어가 필요하나, 피드 쿼리에 가시성 필터링 로직이 분산되어 관리 어려움
- **[해결]** `PostVisibilityChecker` 클래스로 가시성 판별 로직을 단일 책임 분리. 피드 쿼리에서 publicity 타입별 조건 분기(OFFICIAL: 팔로잉 전체, CIRCLE: 서클 팔로워만, PERSONAL: 본인만)
- **[결과]** 가시성 로직 일원화로 버그 방지 및 유지보수성 향상

### 1-3. 게시글 CRUD + 임시저장/발행
- **[문제]** 사용자가 4개 질문에 답변을 작성하는 중간에 이탈할 수 있어, 작성 중 데이터 유실 위험
- **[해결]** `TEMP(임시저장) → PUBLISHED(발행)` 2단계 상태 관리. 임시 게시글은 24시간 후 자동 정리(배치 스케줄러)
- **[결과]** 작성 중 이탈 시에도 임시 저장본 복구 가능, 만료된 임시글은 자동 정리

### 1-4. Push/Pull 하이브리드 피드 아키텍처
- **[문제]** Pull-only 피드 아키텍처에서 DTO 캐시 TTL(30초) 만료 시 다수 요청이 동시에 DB를 조회하는 Thundering Herd 발생. 팔로워 5000명 기준 p95 2.56s
- **[해결]** Redis Sorted Set 기반 인박스(Fan-out-on-write) + DTO 캐시 + Pull fallback 3계층 구조 설계. 게시글 발행 시 팔로워 인박스에 비동기 push, 조회 시 캐시 → 인박스 → Pull 순서로 탐색. 셀럽 임계값(팔로워 5000+)은 fan-out 생략하고 Pull fallback으로 처리
- **[결과]** Feed p95 2.2s → 522ms (76.3% 개선), 팔로워 50~5000명 구간에서 375~699ms 균일 성능 달성

### 1-5. 피드 캐싱 (Caffeine + Redis 2계층)
- **[문제]** 매 피드 요청마다 팔로우 목록 조회 + DB 쿼리 실행으로 불필요한 반복 연산
- **[해결]** Caffeine 로컬 캐시(followingIds, circleIds — TTL 5분, 5000건)로 팔로우 ID 캐싱. Redis에 피드 DTO 캐시(TTL 30초) + SETNX 분산 락으로 Thundering Herd 방지
- **[결과]** 캐시 HIT 시 follow 테이블 접근 0회, Prometheus 경로 분포: cache 59.5%, inbox 36.7%, pull 3.7%

### 1-6. 피드 Edge Case 처리
- **[문제]** 차단/언팔로우/서클 변경 시 인박스에 잔존하는 게시글이 노출되어 데이터 정합성 문제
- **[해결]** 각 이벤트별 비동기 리스너로 즉시 인박스 정리 — Block: 양방향 게시글 제거, Unfollow: 상대 게시글 제거, Follow: 최근 게시글 백필, Circle 변경: CORE 인박스 동기화
- **[결과]** 관계 변경 즉시 피드 반영, 캐시 동기 무효화(+1~5ms)로 stale 데이터 노출 방지

---

## 2. 좋아요 & 댓글 시스템

### 2-1. 좋아요 토글 + Atomic Counter
- **[문제]** 좋아요 생성/삭제 시 Post 엔티티의 likeCount를 애플리케이션 레벨에서 증감하면, 동시 요청 시 Lost Update 발생 가능
- **[해결]** `UPDATE post SET like_count = like_count + 1 WHERE id = ?` DB Atomic UPDATE 사용. 별도 `PostCountEvent` 발행 후 전용 `countExecutor` 스레드풀에서 별도 트랜잭션(REQUIRES_NEW)으로 처리하여 FK 데드락 방지
- **[결과]** 200 VU 동시성 테스트에서 Lost Update 0%, 평균 락 대기 54ms

### 2-2. 좋아요 체크 쿼리 최적화
- **[문제]** 좋아요 여부 확인 시 Member + Post 엔티티를 각각 로딩(3개 쿼리)한 뒤 조회하는 비효율
- **[해결]** `existsByMemberIdAndPostId(Long, Long)` ID 기반 존재 여부 쿼리로 변경, 엔티티 로딩 제거
- **[결과]** 좋아요 체크: 3 쿼리 → 1 쿼리 (67% 감소)

### 2-3. 계층형 댓글 시스템 (댓글 + 답글)
- **[문제]** 댓글에 대한 답글 지원이 필요하나, 무한 depth는 UI 복잡도와 쿼리 비용 증가
- **[해결]** depth 필드(0=댓글, 1=답글) 2단계 계층 구조 + parent_id 자기참조. 커서 기반 페이지네이션으로 댓글/답글 분리 조회
- **[결과]** 댓글과 답글을 독립적으로 페이지네이션 가능, depth 제한으로 쿼리 복잡도 일정

### 2-4. 댓글 Soft Delete
- **[문제]** 댓글 물리 삭제 시 하위 답글의 참조 무결성 깨짐 + "삭제된 댓글입니다" 표시 불가
- **[해결]** `isDeleted` 플래그 기반 논리 삭제, 조회 시 삭제된 댓글 필터링. 답글이 있는 댓글은 내용만 마스킹
- **[결과]** 참조 무결성 유지 + 답글 트리 구조 보존

### 2-5. 댓글 N+1 쿼리 제거
- **[문제]** 댓글 목록 조회 시 각 댓글마다 답글 컬렉션 Lazy Loading → 10개 댓글 = 30+개 쿼리
- **[해결]** replyCount를 `GROUP BY parent_id` 배치 집계 쿼리로 변경 (`countRepliesByParentIds()`). member + profileImg에 `leftJoin().fetchJoin()` 추가로 개별 로딩 제거
- **[결과]** 댓글 조회: ~30+ 쿼리 → 4 쿼리 (87% 감소)

### 2-6. 차단 시 좋아요/댓글 일괄 정리
- **[문제]** 사용자 차단 시 양방향 좋아요/댓글이 잔존하여 차단 의미 무력화
- **[해결]** `BlockedEvent` 발행 → `InteractionCleanupListener`에서 양방향 좋아요 물리 삭제 + 댓글 soft delete + 카운트 보정(`decrementLikeCountByAmount`, `decrementCommentCountByAmount`)
- **[결과]** 차단 즉시 양방향 상호작용 정리 완료, 비동기 처리로 차단 API 응답 지연 없음

---

## 3. 비동기 알림 시스템

### 3-1. 이벤트 기반 알림 발송 (좋아요/댓글/팔로우)
- **[문제]** 좋아요/댓글 API에서 FCM 푸시 알림을 동기적으로 호출하여 200ms+ 지연이 API 응답 시간에 직접 전파. 서비스 의존성 5~6개로 테스트 어려움
- **[해결]** `NotificationEvent` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")` 패턴 도입. 이벤트에는 엔티티가 아닌 ID만 전달(별도 트랜잭션 컨텍스트)
- **[결과]** PostLikeCommandServiceImpl 의존성 5→3개, CommentCommandServiceImpl 6→4개. FCM 실패가 비즈니스 로직에 무영향

### 3-2. FCM Circuit Breaker
- **[문제]** FCM 서버 장애 시 모든 알림 스레드가 타임아웃 대기 → 스레드풀 고갈 → 전체 서비스 영향
- **[해결]** 수동 Circuit Breaker 구현 (CLOSED → OPEN → HALF_OPEN). FAILURE_THRESHOLD=5, OPEN_DURATION=30초. 영구 오류(INVALID_ARGUMENT, UNREGISTERED)는 실패 카운트 제외
- **[결과]** FCM 장애 시 30초 내 Circuit Open → 불필요한 호출 차단 → 스레드풀 보호

### 3-3. CallerRunsPolicy 큐 오버플로 방어
- **[문제]** notificationExecutor 큐(용량 100) 초과 시 AbortPolicy로 RejectedExecutionException → HTTP 500
- **[해결]** 큐 용량 100→500 확장 + RejectedExecutionHandler를 CallerRunsPolicy로 변경 (큐 초과 시 호출 스레드가 직접 실행)
- **[결과]** 큐 오버플로 시에도 HTTP 500 = 0, 호출 스레드에서 동기 처리로 graceful degradation

### 3-4. 이중 토큰 검증 제거
- **[문제]** FCM 발송 전 `isTokenValid()`에서 실제 FCM 전송(`FirebaseMessaging.send()`)으로 토큰 유효성 검증 → 기기당 FCM 2회 호출(400ms)
- **[해결]** `isTokenValid()`를 null/blank 문자열 체크로 단순화 → 기기당 1회 호출(200ms)
- **[결과]** 알림 처리량 2배 향상 (25 → 50 notifs/sec), 불필요한 네트워크 호출 제거

### 3-5. 알림 리마인더 스케줄러 최적화
- **[문제]** 5분 주기 리마인더 스케줄러가 대상 멤버당 4~8개 쿼리 실행 (50명 = ~600 쿼리) + FCM 동기 호출로 스케줄러 스레드 13분+ 블로킹
- **[해결]** 배치 쿼리 2개로 통합(TodayQuestion IN절 + PostAnswer IN절 + HashMap 룩업). FCM 호출을 `notificationExecutor.execute()`로 비동기 위임
- **[결과]** 쿼리 ~600개 → 2개 (99.7% 감소), 스케줄러 스레드 수초 내 반환

### 3-6. 알림 Retry + Observability
- **[문제]** FCM 일시적 오류 시 재시도 없이 알림 유실 + 알림 파이프라인 상태 파악 불가
- **[해결]** 1회 재시도 + 100ms backoff. Micrometer 메트릭 6종 등록: send.duration(Timer), send.success/failure/retry/skip(Counter), circuit.state(Gauge)
- **[결과]** 일시적 오류 복구 + Prometheus/Grafana로 실시간 파이프라인 모니터링

### 3-7. SLF4J 로깅 전환
- **[문제]** 알림 관련 코드에서 `System.out.println` / `System.err.println` 사용 → 로그 레벨 구분 불가, 로그 검색 불가
- **[해결]** 전체 `System.out/err` → `log.info/warn/error` (SLF4J) 전환
- **[결과]** 로그 레벨별 필터링 + 로그 집계 시스템(ELK 등) 연동 가능

---

## 4. 이미지 처리

### 4-1. S3 이미지 업로드/다운로드 파이프라인
- **[문제]** 게시글 답변에 이미지 첨부 시 안정적인 파일 저장소 필요
- **[해결]** AWS S3 기반 이미지 저장. 고유 파일명 생성(UUID), MIME 타입 검증, 프로필 이미지/게시글 이미지 경로 분리
- **[결과]** 안정적인 이미지 저장/조회 파이프라인 구축

### 4-2. @Profile 기반 로컬 스텁
- **[문제]** 로컬 개발/테스트 시 S3, FCM, Mail 등 외부 서비스 의존성으로 부하 테스트 불가
- **[해결]** `@Profile("local")` 스텁 구현: FcmServiceStub(더미 발송), LocalImageStorageStub(로컬 파일시스템) 등. `@Profile("!local")`로 운영 구현체 자동 전환
- **[결과]** 외부 의존성 없이 918MB 시드 데이터 기반 부하 테스트 가능

---

## 5. 성능 최적화 (9단계 측정 기반)

### 5-1. 측정 환경 구축 (Phase 0)
- **[문제]** "빠르다/느리다"의 감 기반 판단 → 병목 지점 특정 불가
- **[해결]** p6spy SQL 로깅으로 API별 쿼리 수 측정, EXPLAIN 분석으로 실행 계획 확인. k6 부하 테스트 스크립트(5→7개 시나리오), 918MB 시드 데이터(10K 유저, 200K 게시글) 생성
- **[결과]** Baseline 측정: p95=518ms, 86 req/s, 0.01% 에러율. 14개 API의 쿼리 수와 심각도 매핑 완료

### 5-2. DB 인덱스 전략 (Phase 1)
- **[문제]** EXPLAIN 분석 결과 5개 테이블에서 Full Table Scan 또는 비효율적 필터링 발생. TodayQuestion: rows 212, filtered 1.11%
- **[해결]** 5개 테이블에 7개 복합 인덱스 추가. `@Transactional(readOnly=true)` 적용으로 Hibernate dirty checking 비활성화. HikariCP 튜닝(max:20, timeout:5s), batch_fetch_size:100
- **[결과]** p95 518ms → 266ms (-48.6%), TodayQuestion rows 212→1, filtered 1.11%→100%

### 5-3. N+1 쿼리 제거 (Phase 2)
- **[문제]** 피드 10건 조회 시 16~17개 쿼리 (TodayQuestion N+1), 댓글 10건 시 30+개 쿼리 (replies + member + profileImg lazy loading)
- **[해결]** TodayQuestion 배치 쿼리 + HashMap 룩업 + @EntityGraph. Comment replyCount GROUP BY 집계. member/profileImg fetchJoin
- **[결과]** 피드: 16→7 쿼리 (-56%), 댓글: ~30+→4 쿼리 (-87%)

### 5-4. 비동기 이벤트 전환 (Phase 3)
- **[문제]** 좋아요/댓글 API에서 FCM을 동기 호출하여 200ms+ 지연이 응답 시간에 전파
- **[해결]** NotificationEvent + @TransactionalEventListener(AFTER_COMMIT) + @Async 패턴으로 분리
- **[결과]** http_req p95 317ms → 270ms (-14.8%), Feed p95 290ms → 262ms (-9.7%)

### 5-5. 멀티 레이어 캐싱 (Phase 4)
- **[문제]** 매 요청마다 follow 테이블 서브쿼리 실행, 질문 컨텐츠 개별 조회
- **[해결]** Caffeine 로컬 캐시(followingIds, circleIds TTL 5분) + 이벤트 기반 @CacheEvict. 질문 4개 개별 → 1개 배치 쿼리
- **[결과]** 캐시 HIT 시 follow 쿼리 0회, 게시글 상세: ~10+→6 쿼리 (-40%)

### 5-6. 배치 처리 최적화 (Phase 5)
- **[문제]** 임시 게시글 정리가 전체 레코드를 메모리 로딩(OOM 위험). 4개 통계 배치가 순차 실행
- **[해결]** 100건 단위 청크 기반 페이지네이션. CompletableFuture + batchExecutor로 4개 통계 병렬 실행
- **[결과]** OOM 위험 제거, 배치 시간: T1+T2+T3+T4 → max(T1,T2,T3,T4) (~75% 단축)

### 5-7. 하이브리드 피드 + 장애 복원력 (Phase 7~8)
- **[문제]** Pull-only 피드의 Thundering Herd + Redis 단일 장애점 + 셀럽 계정 fan-out 부하
- **[해결]** Push/Pull 하이브리드 피드(Phase 7) + Redis Circuit Breaker + 500명 청크 fan-out + 2회 재시도(Phase 8)
- **[결과]** Feed p95 2.2s→522ms (76.3% 개선), Redis kill 시 전체 서비스 무중단 확인

### 5-8. 알림 파이프라인 장애 복원력 (Phase 9)
- **[문제]** FCM 장애 시 스레드풀 고갈, 큐 오버플로 시 HTTP 500
- **[해결]** FCM Circuit Breaker + CallerRunsPolicy + 이중 토큰 검증 제거 + 스케줄러 비동기화
- **[결과]** Story1(코드): p95 3.83s→3.06s (-20%), 48→58 req/s (+19%). Story2(인프라 분리): p95 2.96s→226ms (-92%), 71→174 req/s (+144%)

### 5-9. 트러블슈팅 — 대량 데이터에서 발견된 버그
- **[문제]** 918MB 시드 데이터 부하 테스트에서 에러율 44% — CORE 피드 500 에러 다발
- **[해결]** 원인: TodayQuestion 테이블에 UNIQUE 제약 조건 미설정 → 중복 데이터 → `findBy` Optional이 `getSingleResult()` 호출 시 NonUniqueResultException. `findBy` → `findFirstBy`(LIMIT 1)로 변경 + `.get()` → `.ifPresent()` 안전 처리
- **[결과]** 에러율 44% → 0.01%, CORE 피드 정상 동작. **대량 데이터 테스트의 중요성 체감**

---

## 6. 인프라 & 모니터링

### 6-1. GCP 인프라 IaC (Terraform)
- **[문제]** 성능 테스트 환경을 수동으로 구축하면 재현 불가 + 비용 관리 어려움
- **[해결]** Terraform으로 GCP 인프라 코드화: App VM(e2-standard-2/4), Redis 전용 VM, Monitoring VM. VPC/서브넷/방화벽 규칙 포함. Makefile로 deploy/seed/test/ssh 자동화
- **[결과]** `make apply`로 전체 인프라 2분 내 생성, `make destroy`로 즉시 정리. 환경 재현 100%

### 6-2. Redis 인프라 분리
- **[문제]** App VM에 Redis를 같이 띄우면 CPU 경합으로 성능 저하 (2vCPU 포화)
- **[해결]** Redis 전용 VM 분리 (Terraform redis.tf) + App VM 4vCPU로 스케일업
- **[결과]** 동일 코드로 p95 2.96s → 226ms (-92%), 처리량 71→174 req/s (+144%). **"병목이 코드가 아닌 인프라"를 측정으로 증명**

### 6-3. Prometheus + Grafana 모니터링
- **[문제]** 운영 중 병목 지점, 캐시 적중률, 스레드풀 상태를 실시간으로 파악할 수 없음
- **[해결]** Spring Actuator + Micrometer로 커스텀 메트릭 50+ 등록. Grafana 대시보드 7패널 구성: 피드 경로 분포(pie), 레이턴시 p50/p95/p99(time series), fan-out 레이턴시, Circuit Breaker 상태, Caffeine 적중률, 스레드풀 사용률, retry/failure 추이
- **[결과]** 실시간 성능 모니터링 + 장애 감지 체계 구축

### 6-4. 부하 테스트 자동화 (k6)
- **[문제]** 최적화 효과를 정량적으로 검증할 도구가 없음
- **[해결]** k6 테스트 스크립트 8종 작성: 통합 부하(80% read/20% write), 피드 확장성(팔로워 50~5000), 핫 키 스트레스, 알림 스트레스, 동시성, 서클 가시성. Power-law 분포 시드 데이터(T1~T4 티어)
- **[결과]** 매 Phase마다 Before/After 정량 비교 가능, 9단계 전체 측정 기반 최적화 완수

---

## 7. 공통 아키텍처 & 설계

### 7-1. Hexagonal Architecture (포트 & 어댑터)
- **[문제]** 도메인 로직이 JPA 구현체에 직접 의존하면 테스트와 기술 교체가 어려움
- **[해결]** Domain Interface(PostRepository) → Adapter(PostRepositoryAdaptor) → JPA/QueryDSL Repository 3계층 구조. 도메인 레이어는 인프라 기술에 무의존
- **[결과]** 도메인 로직 단위 테스트 용이, QueryDSL ↔ JPA 혼합 사용 가능

### 7-2. CQRS 패턴 (Command/Query 분리)
- **[문제]** 읽기/쓰기 로직이 하나의 서비스에 혼재하면 트랜잭션 최적화(readOnly) 적용 어려움
- **[해결]** 모든 도메인에 CommandService(쓰기) / QueryService(읽기) 분리. QueryService에 `@Transactional(readOnly=true)` 일괄 적용
- **[결과]** 읽기 전용 트랜잭션에서 Hibernate dirty checking 비활성화 → DB 부하 감소

### 7-3. 5개 전용 스레드풀 분리
- **[문제]** 단일 스레드풀에서 모든 비동기 작업 처리 시, FCM 장애로 스레드 고갈되면 전체 비동기 작업 중단
- **[해결]** 용도별 전용 Executor 5개 분리: mailExecutor(2-5), countExecutor(4-16), batchExecutor(4-8), fanoutExecutor(4-8), notificationExecutor(4-10). 각각 독립 큐 + CallerRunsPolicy
- **[결과]** FCM 장애가 fan-out/batch/count 작업에 영향 없음 (장애 격리)

### 7-4. Redis Circuit Breaker
- **[문제]** Redis 장애 시 피드 인박스 + DTO 캐시 모두 실패 → 전체 피드 서비스 중단
- **[해결]** 수동 Circuit Breaker(CLOSED→OPEN→HALF_OPEN) 구현. FAILURE_THRESHOLD=5, OPEN_DURATION=10초. OPEN 시 즉시 fallback(Pull 쿼리) 반환, HALF_OPEN에서 1건 시도 후 복구 판단
- **[결과]** Redis kill 테스트에서 피드 HTTP 200 유지 + 신규 로그인 성공 — 전체 서비스 무중단 확인

### 7-5. JWT 인증 최적화
- **[문제]** 매 요청마다 DB에서 사용자 정보 조회하는 `PrincipalDetailsService.loadUserByUsername()` 호출
- **[해결]** `@Cacheable("userDetails")` 적용 (TTL 10분, 10000건), 로그인/프로필 변경 시 @CacheEvict
- **[결과]** 인증 DB 조회 캐시 적중 시 0회, 인증 레이어 성능 최적화

### 7-6. 기술 의사결정 (ADR)
- **[문제]** 기술 선택의 근거가 문서화되지 않으면 추후 "왜 이걸 썼지?" 질문에 답할 수 없음
- **[해결]** 주요 의사결정마다 ADR 작성: Spring Batch 미채택(현재 규모에서 메타데이터 테이블 9개 오버헤드), Redis Streams 미채택(in-memory @Async로 충분), Caffeine vs Redis(단일 서버에서 네트워크 없는 로컬 캐시 우선), Resilience4j 미채택(수동 CB가 이해/디버깅 용이)
- **[결과]** 기술 선택마다 "왜 선택했고, 왜 대안을 버렸는지" 추적 가능
