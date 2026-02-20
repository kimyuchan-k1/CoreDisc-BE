package com.coredisc.application.event;

import com.coredisc.application.service.fcm.FcmService;
import com.coredisc.application.service.notification.NotificationCommandService;
import com.coredisc.domain.device.Device;
import com.coredisc.domain.device.DeviceRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import com.coredisc.presentation.dto.notification.NotificationRequestDTO;
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
public class NotificationEventListener {

    private final NotificationCommandService notificationCommandService;
    private final DeviceRepository deviceRepository;
    private final MemberRepository memberRepository;
    private final FcmService fcmService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationEvent(NotificationEvent event) {
        try {
            Member sender = memberRepository.findById(event.getSenderId()).orElse(null);
            Member receiver = memberRepository.findById(event.getReceiverId()).orElse(null);

            if (sender == null || receiver == null) {
                log.warn("알림 전송 실패: sender={} receiver={} 존재하지 않음", event.getSenderId(), event.getReceiverId());
                return;
            }

            // DB에 알림 저장
            notificationCommandService.createNotification(
                    new NotificationRequestDTO(
                            event.getType(),
                            sender,
                            receiver,
                            event.getContent(),
                            event.getTargetId()
                    )
            );

            // FCM 푸시 알림 전송
            List<Device> devices = deviceRepository.findByMemberAndIsActiveTrue(receiver);
            String title = "CoreDisc";
            String body = event.getContent();

            Map<String, String> data = new HashMap<>();
            data.put("notificationType", event.getType().name());
            data.put("targetId", String.valueOf(event.getTargetId()));

            for (Device device : devices) {
                String token = device.getToken();
                if (fcmService.isTokenValid(token)) {
                    fcmService.sendNotificationToToken(token, title, body, data);
                    log.info("[ASYNC] 알림 발송됨: type={}, receiverId={}", event.getType(), receiver.getId());
                }
            }
        } catch (Exception e) {
            log.error("[ASYNC] 알림 처리 실패: type={}, error={}", event.getType(), e.getMessage(), e);
        }
    }
}
