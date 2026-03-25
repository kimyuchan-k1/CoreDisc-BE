package com.coredisc.application.event;

import com.coredisc.application.service.fcm.FcmService;
import com.coredisc.application.service.notification.NotificationCommandService;
import com.coredisc.config.FcmCircuitBreaker;
import com.coredisc.domain.common.enums.NotificationType;
import com.coredisc.domain.device.Device;
import com.coredisc.domain.device.DeviceRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import com.coredisc.presentation.dto.notification.NotificationRequestDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowedEventListener {

    private final NotificationCommandService notificationCommandService;
    private final DeviceRepository deviceRepository;
    private final MemberRepository memberRepository;
    private final FcmService fcmService;
    private final FcmCircuitBreaker fcmCircuitBreaker;
    private final MeterRegistry meterRegistry;

    private static final int MAX_RETRIES = 1;
    private static final long RETRY_INTERVAL_MS = 100;

    private Timer sendDuration;
    private Counter successCounter;
    private Counter failureCounter;
    private Counter retryCounter;
    private Counter skipCounter;

    @PostConstruct
    void initMetrics() {
        sendDuration = Timer.builder("notification.send.duration")
                .tag("type", "follow")
                .description("팔로우 알림 처리 전체 시간")
                .register(meterRegistry);
        successCounter = Counter.builder("notification.send.success")
                .tag("type", "follow")
                .description("팔로우 알림 성공 건수")
                .register(meterRegistry);
        failureCounter = Counter.builder("notification.send.failure")
                .tag("type", "follow")
                .description("팔로우 알림 영구 실패 건수")
                .register(meterRegistry);
        retryCounter = Counter.builder("notification.send.retry")
                .description("팔로우 알림 재시도 횟수")
                .register(meterRegistry);
        skipCounter = Counter.builder("notification.fcm.skip")
                .description("CB OPEN으로 스킵된 건수 (follow)")
                .register(meterRegistry);
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFollowedEvent(FollowedEvent event) {
        sendDuration.record(() -> {
            try {
                Member sender = memberRepository.findById(event.getActorId()).orElse(null);
                Member receiver = memberRepository.findById(event.getTargetId()).orElse(null);

                if (sender == null || receiver == null) {
                    log.warn("팔로우 알림 전송 실패: sender={} receiver={} 존재하지 않음",
                            event.getActorId(), event.getTargetId());
                    return;
                }

                String content = event.getActorNickname() + "님이 팔로우를 시작했어요.";

                // DB에 알림 저장
                notificationCommandService.createNotification(
                        new NotificationRequestDTO(
                                NotificationType.FOLLOW,
                                sender,
                                receiver,
                                content,
                                sender.getId()
                        )
                );

                // FCM 푸시 알림 전송
                List<Device> devices = deviceRepository.findByMemberAndIsActiveTrue(receiver);
                String title = "CoreDisc";

                Map<String, String> data = new HashMap<>();
                data.put("notificationType", NotificationType.FOLLOW.name());
                data.put("targetId", String.valueOf(sender.getId()));
                data.put("username", event.getActorNickname());

                for (Device device : devices) {
                    String token = device.getToken();
                    if (!fcmService.isTokenValid(token)) continue;

                    if (!fcmCircuitBreaker.isAvailable()) {
                        skipCounter.increment();
                        log.debug("[FOLLOW] CB OPEN — FCM 스킵: receiverId={}", receiver.getId());
                        continue;
                    }

                    sendWithRetry(token, title, content, data, receiver.getId());
                }
            } catch (Exception e) {
                failureCounter.increment();
                log.error("[FOLLOW] 팔로우 알림 처리 실패: error={}", e.getMessage(), e);
            }
        });
    }

    private void sendWithRetry(String token, String title, String body,
                                Map<String, String> data, Long receiverId) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    retryCounter.increment();
                    Thread.sleep(RETRY_INTERVAL_MS);
                }
                fcmService.sendNotificationToToken(token, title, body, data);
                successCounter.increment();
                log.info("[FOLLOW] 팔로우 알림 발송됨: receiverId={}", receiverId);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    failureCounter.increment();
                    log.error("[FOLLOW] 영구 실패: receiverId={}, error={}", receiverId, e.getMessage());
                }
            }
        }
    }
}
