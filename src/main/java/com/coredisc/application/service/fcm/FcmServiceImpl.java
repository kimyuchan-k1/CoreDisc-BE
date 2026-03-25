package com.coredisc.application.service.fcm;

import com.coredisc.config.FcmCircuitBreaker;
import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("!local")
public class FcmServiceImpl implements FcmService {

    private final FcmCircuitBreaker fcmCircuitBreaker;

    /** 영구 실패 — 재시도해도 복구 불가, CB에 카운트하지 않음 */
    private static final Set<MessagingErrorCode> PERMANENT_ERRORS = Set.of(
            MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.UNREGISTERED
    );

    @Override
    public void sendNotificationToToken(String token, String title, String body, Map<String, String> data) {
        if (!fcmCircuitBreaker.isAvailable()) {
            log.warn("[FCM] Circuit OPEN — 전송 스킵: token={}", token);
            return;
        }

        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putAllData(data)
                .build();

        try {
            String response = FirebaseMessaging.getInstance().send(message);
            fcmCircuitBreaker.recordSuccess();
            log.info("[FCM] 푸시 알림 성공: {}", response);
        } catch (FirebaseMessagingException e) {
            if (PERMANENT_ERRORS.contains(e.getMessagingErrorCode())) {
                // 영구 실패: 토큰 문제이므로 CB 카운트 안 함
                log.warn("[FCM] 영구 실패 ({}): token={}", e.getMessagingErrorCode(), token);
            } else {
                // 일시 실패: INTERNAL, UNAVAILABLE 등 — CB 카운트
                fcmCircuitBreaker.recordFailure();
                log.error("[FCM] 일시 실패 ({}): token={}, msg={}",
                        e.getMessagingErrorCode(), token, e.getMessage());
            }
        }
    }

    @Override
    public boolean isTokenValid(String token) {
        return token != null && !token.isBlank();
    }
}
