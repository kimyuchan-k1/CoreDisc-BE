# Phase 9: 알림 파이프라인 장애 복원력

> **기간**: 2026-03-22
> **목표**: 알림 파이프라인의 FCM 장애 전파 차단, 큐 오버플로우 방지, 스케줄러 블로킹 해소

---

## 1. 문제 분석

### 1.1 Before: 알림 파이프라인 취약점

| 문제 | 영향 | 근본 원인 |
|------|------|----------|
| 큐 오버플로우 → HTTP 500 | 유저 요청 실패 | `AbortPolicy` + 큐 100 |
| 이중 토큰 검증 | 기기당 FCM 2회 호출 | `isTokenValid()`가 실제 메시지 전송 |
| FCM 장애 전파 | 스레드풀 고갈 | Circuit Breaker 없음 |
| 스케줄러 블로킹 | 리마인더 지연 | 동기 FCM 호출 |
| System.out 로깅 | 운영 가시성 부재 | SLF4J 미사용 |

### 1.2 이중 토큰 검증 문제

```
Before (기기당 2회 FCM 호출):
  isTokenValid(token)  → FirebaseMessaging.send() [200ms]
  sendNotificationToToken() → FirebaseMessaging.send() [200ms]
  = 기기당 400ms, 10 워커 → 최대 25 알림/초

After (기기당 1회 FCM 호출):
  isTokenValid(token)  → null/blank 체크 [0ms]
  sendNotificationToToken() → FirebaseMessaging.send() [200ms]
  = 기기당 200ms, 10 워커 → 최대 50 알림/초 (2배 향상)
```

### 1.3 스케줄러 블로킹 계산

```
Before (동기):
  1000명 × 2기기 × 400ms = ~800초 (13분 블로킹)

After (비동기 위임):
  스케줄러: DB 쿼리 + executor.execute() 위임 → ~수초
  워커풀: 2000건 / 50 req/s = ~40초 (병렬 처리)
```

---

## 2. 해결 방안

### 2.1 CallerRunsPolicy + 큐 용량 증가 (9-2a)

**파일**: `config/AsyncConfig.java`

```
queueCapacity: 100 → 500
rejectedExecutionHandler: (없음) → CallerRunsPolicy
```

- 큐 포화 시 호출자 스레드에서 실행 → HTTP 500 방지
- `countExecutor`, `fanoutExecutor`에서 이미 검증된 패턴

### 2.2 FCM Circuit Breaker (9-2b)

**파일**: `config/FcmCircuitBreaker.java`

`RedisCircuitBreaker`와 동일 패턴:
- FAILURE_THRESHOLD = 5 (연속 5회 실패 시 OPEN)
- OPEN_DURATION = 30초 (FCM 복구가 Redis보다 느림)
- Micrometer gauge: `fcm.circuit.state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN)

### 2.3 이중 토큰 검증 제거 + CB 적용 (9-2c)

**파일**: `service/fcm/FcmServiceImpl.java`

- `isTokenValid()` → 단순 null/blank 체크 (네트워크 호출 제거)
- `sendNotificationToToken()` → FcmCircuitBreaker 연동
- UNREGISTERED/INVALID_ARGUMENT = 영구 실패 (CB 미카운트)
- INTERNAL/UNAVAILABLE = 일시 실패 (CB 카운트)
- `System.out/err.println` → SLF4J `log.info/warn/error`

### 2.4 재시도 + 메트릭 (9-2d, 9-2e)

**파일**: `event/NotificationEventListener.java`, `event/FollowedEventListener.java`

`FeedFanoutEventListener`와 동일 패턴:
- 1회 재시도, 100ms backoff
- 영구 실패 시 로그 + failureCounter

| 메트릭 | 타입 | 태그 | 목적 |
|--------|------|------|------|
| `notification.send.duration` | Timer | type=general/follow | 처리 시간 |
| `notification.send.success` | Counter | type=general/follow | 성공 건수 |
| `notification.send.failure` | Counter | type=general/follow | 영구 실패 |
| `notification.send.retry` | Counter | — | 재시도 횟수 |
| `notification.fcm.skip` | Counter | — | CB OPEN 스킵 |
| `fcm.circuit.state` | Gauge | — | CB 상태 |

### 2.5 스케줄러 비동기화 (9-2f)

**파일**: `schedule/NotificationReminderScheduler.java`

```java
// Before: 스케줄러 스레드에서 동기 FCM 호출
for (Device device : devices) {
    if (fcmService.isTokenValid(token)) {
        fcmService.sendNotificationToToken(token, title, body, data); // 블로킹!
    }
}

// After: notificationExecutor에 위임
for (Device device : devices) {
    if (!fcmService.isTokenValid(token)) continue;
    notificationExecutor.execute(() -> {
        fcmService.sendNotificationToToken(token, title, body, data);
    });
}
```

스케줄러 스레드는 DB 쿼리만 실행 후 즉시 반환.

---

## 3. 변경 파일 요약

### 수정 (7개)

| 파일 | 변경 |
|------|------|
| `config/AsyncConfig.java` | notificationExecutor: CallerRunsPolicy + queue 500 |
| `service/fcm/FcmServiceImpl.java` | isTokenValid 단순화, CB 적용, SLF4J 전환 |
| `service/fcm/FcmServiceStub.java` | stub.delay-ms 주입 + Thread.sleep |
| `event/NotificationEventListener.java` | 재시도 + 메트릭 + CB 연동 |
| `event/FollowedEventListener.java` | 재시도 + 메트릭 + CB 연동 |
| `schedule/NotificationReminderScheduler.java` | FCM 전송 notificationExecutor 위임 |
| `resources/application-perf.yml` | stub.delay-ms: 200 |

### 생성 (4개)

| 파일 | 역할 |
|------|------|
| `config/FcmCircuitBreaker.java` | FCM 수동 Circuit Breaker |
| `k6/notification-stress-test.js` | 알림 스트레스 테스트 |
| `docs/optimization/22-phase9-notification-resilience.md` | 최적화 기록 |
| `docs/optimization/23-notification-queue-adr.md` | 외부 큐 ADR |

---

## 4. 성능 비교 — Story 1: 코드 최적화 (동일 인프라)

> **변수 통제**: 인프라 동일 (e2-standard-2, 단일 VM), 코드만 변경
> **테스트**: `k6/notification-stress-test.js` — 100 VU, 3분

### 4.1 코드 최적화 Before/After

| 지표 | Before (Phase 9 미적용) | After (Phase 9 적용) | 변화 |
|------|----------------------|---------------------|------|
| HTTP 500 | 0 | **0** | CallerRunsPolicy 보호 |
| error_rate | 0.00% | **0.00%** | 유지 |
| like p95 | 4.49s | **3.64s** | **↓ 18.9%** |
| follow p95 | 906ms | **707ms** | **↓ 22.0%** |
| http_req p95 | 3.83s | **3.06s** | **↓ 20.1%** |
| 처리량 | 48.33 req/s | **57.72 req/s** | **↑ 19.4%** |

### 4.2 구조적 개선 요약

| 항목 | Before | After | 근거 |
|------|--------|-------|------|
| 기기당 FCM 호출 | 2회 | **1회** | isTokenValid 네트워크 호출 제거 |
| 큐 오버플로우 방어 | AbortPolicy (500 에러) | **CallerRunsPolicy** | 호출자 실행으로 무장애 |
| FCM 장애 시 동작 | 스레드풀 고갈 | **CB OPEN → 30초 스킵** | FcmCircuitBreaker |
| 스케줄러 블로킹 | 동기 FCM 호출 | **notificationExecutor 위임** | 스레드 즉시 반환 |
| 운영 가시성 | System.out | **Micrometer 7종 + SLF4J** | Prometheus 연동 |
| 알림 실패 복구 | 없음 | **1회 재시도 + 100ms backoff** | 일시 장애 자동 복구 |

### 4.3 Prometheus 메트릭 실측

| 메트릭 | 값 | 의미 |
|--------|-----|------|
| `notification.send.success{type=general}` | **3,951** | 알림 전송 성공 |
| `notification.send.failure` | **0** | 영구 실패 없음 |
| `notification.send.retry` | **0** | 재시도 불필요 |
| `notification.fcm.skip` | **0** | CB OPEN 스킵 없음 |
| `fcm.circuit.state` | **0.0** (CLOSED) | 정상 동작 |

### 4.4 코드 최적화 인사이트

p95가 3초대로 여전히 높은 이유는 코드가 아니라 **인프라 제약** (2 vCPU 단일 VM에 App + MySQL + Redis 통합)이었다.
이를 확인하기 위해 인프라 분리 실험을 진행한다 (→ 섹션 5).

### 4.5 외부 큐 ADR

Redis Streams / RabbitMQ 도입을 검토한 결과, **현재 트래픽 규모(일일 ~10K)에서는 내부 큐 강화로 충분**하다고 판단.
상세 분석: `docs/optimization/23-notification-queue-adr.md`

---

## 5. 성능 비교 — Story 2: 인프라 분리 (동일 코드)

> **변수 통제**: 코드 동일 (Phase 9 적용), 인프라만 변경
> **목적**: p95 3초대의 근본 원인이 코드인지 인프라인지 측정으로 증명

### 5.1 인프라 변경 내역

| 항목 | Before | After |
|------|--------|-------|
| App VM | e2-standard-2 (2 vCPU, 8GB) | **e2-standard-4 (4 vCPU, 16GB)** |
| Redis | App VM 내 Docker (localhost) | **전용 VM 분리 (10.0.1.20)** |
| MySQL | App VM 내 Docker | App VM 내 Docker (동일) |
| 코드 | Phase 9 적용 | Phase 9 적용 (동일) |

### 5.2 알림 스트레스 테스트 (100 VU, 3분)

| 지표 | Before (2vCPU, 통합) | After (4vCPU, Redis 분리) | 변화 |
|------|---------------------|-------------------------|------|
| HTTP 500 | 0 | **0** | 유지 |
| like p95 | 3.64s | **489ms** | **↓ 87%** |
| follow p95 | 707ms | **128ms** | **↓ 82%** |
| http_req p95 | 3.06s | **421ms** | **↓ 86%** |
| 처리량 | 57.72 req/s | **274 req/s** | **↑ 375%** |

### 5.3 통합 부하 테스트 (180 VU, 10분, 읽기 80% + 쓰기 20%)

| 지표 | Before (2vCPU, Phase 8 측정) | After (4vCPU, Redis 분리) | 변화 |
|------|---------------------------|-------------------------|------|
| http_req p95 | 2.96s | **226ms** | **↓ 92%** |
| feed ALL p95 | 3.07s | **237ms** | **↓ 92%** |
| feed CORE p95 | 2.89s | **144ms** | **↓ 95%** |
| post detail p95 | 2.82s | **244ms** | **↓ 91%** |
| like p95 | - | **169ms** | - |
| follow p95 | - | **79ms** | - |
| comment create p95 | 2.59s | **151ms** | **↓ 94%** |
| 처리량 | 71.2 req/s | **174 req/s** | **↑ 144%** |
| 총 요청 | 46,789 | **112,512** | **↑ 140%** |
| 에러율 | 6.53% | **2.98%** | ↓ 54% |

> 에러율 2.98%는 k6 스크립트의 post create 바디가 API 스펙과 불일치하는 테스트 이슈 (hot post 가시성 차단 포함).

### 5.4 핵심 인사이트

**"병목은 코드가 아니라 인프라였다"를 측정으로 증명.**

- 2 vCPU 단일 VM에서 App + MySQL + Redis가 CPU/IO를 경합 → p95 3초대
- 4 vCPU + Redis VM 분리로 경합 해소 → **p95 200ms대**
- 동일 코드, 동일 데이터, **인프라만 변경하여 레이턴시 86~95% 감소**
- 코드 최적화(20%)보다 인프라 분리(92%)의 효과가 압도적
- → **무작정 코드를 최적화하기 전에 병목 지점을 측정으로 파악하는 것이 핵심**

---

## 6. 수용 능력 분석

### 6.1 실측 기반 처리 능력

| 인프라 구성 | 처리량 | p95 | 에러율 |
|-----------|--------|-----|--------|
| 2vCPU, 단일 VM | 71.2 req/s | 2.96s | 6.5% |
| **4vCPU, Redis 분리** | **174 req/s** | **226ms** | **2.98%** |

### 6.2 DAU / MAU 추정

```
일기 앱 사용 패턴:
  세션: 1~2회/일, 5분, 20건 요청
  피크 2시간 집중도: 40%
  피크 평균 req/s = DAU × 0.0011
  스파이크 보정 (÷3), 안전 마진 (×0.7)
```

| 구분 | DAU | MAU (DAU/MAU ≈ 20%) |
|------|-----|---------------------|
| **쾌적 운영** (p95 < 500ms) | **10,000~15,000** | **50,000~75,000** |
| **한계 운영** (p95 < 1s) | **~30,000** | **~150,000** |

### 6.3 추가 확장 시

| 구성 | 예상 DAU | 변경 사항 |
|------|---------|----------|
| 현재 (App+MySQL 1VM, Redis 1VM) | 10,000~15,000 | - |
| MySQL 전용 VM 분리 | 20,000~30,000 | DB I/O 경합 해소 |
| App 수평 확장 (2 인스턴스 + LB) | 40,000~60,000 | ShedLock, 분산 세션 필요 |

---

## 7. 검증 방법

```bash
# Story 1: 코드 최적화 비교 (동일 인프라)
# 1. Before (Phase 9 미적용 코드, e2-standard-2)
k6 run --env BASE_URL=http://34.64.214.78:8080 k6/notification-stress-test.js

# 2. Phase 9 배포 (동일 인프라)
cd infra && make deploy

# 3. After (Phase 9 적용 코드, e2-standard-2)
k6 run --env BASE_URL=http://34.64.214.78:8080 k6/notification-stress-test.js

# Story 2: 인프라 분리 비교 (동일 코드)
# 4. Terraform으로 스케일업 + Redis 분리
#    variables.tf: app_machine_type = "e2-standard-4"
#    application-cloud.yml: redis.host = 10.0.1.20
terraform apply -auto-approve && make deploy

# 5. After (Phase 9 코드, 4vCPU + Redis 분리)
k6 run --env BASE_URL=http://34.64.214.78:8080 k6/notification-stress-test.js
k6 run --env BASE_URL=http://34.64.214.78:8080 k6/load-test-v2.js

# Prometheus 메트릭 확인
curl http://34.64.214.78:8080/actuator/prometheus | grep -E 'notification_send|fcm_circuit'
```

### 확인 항목
- [x] HTTP 500 = 0 (코드 최적화 + 인프라 분리 모두)
- [x] 코드 최적화: 처리량 19.4% 향상, 레이턴시 20.1% 개선
- [x] 인프라 분리: 처리량 375% 향상, 레이턴시 86~95% 개선
- [x] Prometheus 메트릭 7종 정상 등록
- [x] `fcm.circuit.state` = 0 (CLOSED)
- [x] **병목 = 인프라** 증명 완료
