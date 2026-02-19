package com.coredisc.application.service.fcm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@Profile("local")
public class FcmServiceStub implements FcmService {

    @Override
    public void sendNotificationToToken(String token, String title, String body, Map<String, String> data) {
        log.info("[STUB] FCM 푸시 알림 전송 - token: {}, title: {}, body: {}, data: {}", token, title, body, data);
    }

    @Override
    public boolean isTokenValid(String token) {
        log.info("[STUB] FCM 토큰 유효성 검사 - token: {}", token);
        return true;
    }
}
