package com.coredisc.application.service.fcm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@Profile("local")
public class FcmServiceStub implements FcmService {

    @Value("${stub.delay-ms:0}")
    private long delayMs;

    @Override
    public void sendNotificationToToken(String token, String title, String body, Map<String, String> data) {
        simulateDelay();
        log.info("[STUB] FCM 푸시 알림 전송 - token: {}, title: {}, body: {}", token, title, body);
    }

    @Override
    public boolean isTokenValid(String token) {
        return token != null && !token.isBlank();
    }

    private void simulateDelay() {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
