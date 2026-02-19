package com.coredisc.application.service.auth;

import com.coredisc.domain.common.enums.EmailRequestType;
import jakarta.mail.MessagingException;

public interface MailService {

    void sendEmail(String sendEmail, EmailRequestType emailRequestType) throws MessagingException;
}
