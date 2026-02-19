package com.coredisc.application.service.fcm;

import java.util.Map;

public interface FcmService {

    void sendNotificationToToken(String token, String title, String body, Map<String, String> data);

    boolean isTokenValid(String token);
}
