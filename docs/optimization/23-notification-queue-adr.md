# ADR: 알림 큐 아키텍처 — 외부 큐 도입 여부

> **상태**: 결정 완료 (2026-03-22)
> **결론**: Redis Streams / RabbitMQ 도입하지 않음. 내부 큐(ThreadPool) 강화로 충분.

---

## 배경

Phase 9에서 알림 파이프라인의 장애 복원력을 개선하면서, 외부 메시지 큐 도입을 검토했다.

### 현재 아키텍처
- Spring `@Async` + `ThreadPoolTaskExecutor` (notificationExecutor)
- 큐 용량: 500 (Phase 9-2a에서 100 → 500 증가)
- CallerRunsPolicy: 큐 포화 시 호출자 스레드에서 실행 (HTTP 500 방지)
- FCM Circuit Breaker: 연속 5회 실패 시 30초 차단

---

## 비교 분석

| 기준 | 내부 큐 (ThreadPool) | Redis Streams | RabbitMQ / Kafka |
|------|---------------------|---------------|------------------|
| 이미 배포됨 | O | O (Redis 7) | X (신규 VM) |
| 추가 인프라 비용 | 0 | 0 | 월 $30+ |
| 구현 복잡도 | 낮음 | 중간 | 높음 |
| 재시작 시 유실 | 최대 510건 | 없음 | 없음 |
| 필요 처리량 | ~10K/일 | 수백만/일 | 수천만/일 |
| 소비자 구현 | 불필요 | XREADGROUP 루프 필요 | Consumer 설정 필요 |
| 모니터링 | Micrometer 내장 | 별도 구현 | Management Plugin |

---

## 판단 근거

### 1. 트래픽 규모가 내부 큐로 충분

일기 앱 특성상 알림 트래픽이 제한적:
- 일일 평균: ~10,000 알림 = **0.12/초**
- 피크 (저녁 8-10시): ~20/초
- 강화된 내부 큐 처리 능력: **25/초** (10 워커 × 2.5 req/s at 400ms latency)

### 2. 큐 유실 허용 가능

재시작 시 최대 510건(큐 500 + 워커 10) 유실 가능하지만:
- 알림 데이터는 **DB에 이미 저장된 후** FCM 전송 단계
- 유실되는 것은 푸시 전송뿐, 앱 내 알림 목록은 정상 표시
- 일일 10K 중 510건 = 5% 미만, 재시작은 배포 시에만 발생

### 3. Redis Streams 도입 시 과설계

Redis Streams를 도입하면 다음이 필요:
- `XADD` 프로듀서 구현
- `XREADGROUP` 소비자 루프 (별도 스레드)
- `XACK` 처리 완료 확인
- `XAUTOCLAIM` 미처리 메시지 재배달
- Pending Entry List 모니터링
- 소비자 그룹 관리

단일 인스턴스, 일일 10K 알림에 이 복잡도는 과도.

### 4. CallerRunsPolicy로 backpressure 해결

Phase 9 이전: `AbortPolicy` → 큐 포화 시 `TaskRejectedException` → HTTP 500
Phase 9 이후: `CallerRunsPolicy` → 큐 포화 시 호출자 스레드에서 실행 → 응답 지연은 있지만 실패 없음

---

## 재고 시점

다음 조건 중 하나라도 해당되면 외부 큐 도입을 재검토:

1. **일일 알림 100K 초과** — 내부 큐 처리량 한계 근접
2. **멀티 인스턴스 배포** — 인스턴스 간 알림 중복/유실 방지 필요
3. **전송 보장 필수** — 결제, 법적 알림 등 유실 불가 케이스 추가
4. **FCM 외 채널 추가** — SMS, 이메일 등 멀티채널 알림 파이프라인

---

## 결론

**내부 큐(ThreadPool) 강화로 충분하다.**

Phase 9-2에서 적용한 CallerRunsPolicy + 큐 500 + FCM Circuit Breaker 조합은
현재 트래픽 규모(일일 ~10K)에서 HTTP 500 = 0, 스케줄러 블로킹 해소를 달성한다.
외부 큐는 트래픽 10배 성장 시점에 재검토한다.
