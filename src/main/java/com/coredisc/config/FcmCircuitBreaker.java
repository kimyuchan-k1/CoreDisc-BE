package com.coredisc.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 수동 Circuit Breaker for FCM.
 *
 * CLOSED → OPEN (연속 실패 FAILURE_THRESHOLD회) → HALF_OPEN (OPEN_DURATION_MS 후) → CLOSED
 * FCM은 Redis보다 복구가 느리므로 OPEN_DURATION = 30초.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmCircuitBreaker {

    private final MeterRegistry meterRegistry;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS = 30_000; // 30초

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("fcm.circuit.state", this, cb -> {
            return switch (cb.state) {
                case CLOSED -> 0;
                case OPEN -> 1;
                case HALF_OPEN -> 2;
            };
        });
    }

    public boolean isAvailable() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt.get() >= OPEN_DURATION_MS) {
                    state = State.HALF_OPEN;
                    log.info("[FCM-CIRCUIT] OPEN → HALF_OPEN: FCM 1건 시도 허용");
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> true;
        };
    }

    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            log.info("[FCM-CIRCUIT] HALF_OPEN → CLOSED: FCM 복구 확인");
        }
        state = State.CLOSED;
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN) {
            transitionToOpen();
            return;
        }
        if (failures >= FAILURE_THRESHOLD && state == State.CLOSED) {
            transitionToOpen();
        }
    }

    private void transitionToOpen() {
        state = State.OPEN;
        openedAt.set(System.currentTimeMillis());
        log.warn("[FCM-CIRCUIT] → OPEN: FCM 연속 실패 {}회, {}ms 후 재시도",
                consecutiveFailures.get(), OPEN_DURATION_MS);
    }
}
