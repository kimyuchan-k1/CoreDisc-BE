package com.coredisc.application.service.auth;

import com.coredisc.domain.common.enums.EmailRequestType;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("local")
public class MailServiceStub implements MailService {

    @Override
    public void sendEmail(String sendEmail, EmailRequestType emailRequestType) throws MessagingException {
        log.info("[STUB] 이메일 발송 - 수신자: {}, 요청타입: {}", sendEmail, emailRequestType);
    }
}
