package com.coredisc.application.service.auth;

import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.AuthHandler;
import com.coredisc.common.util.RedisUtil;
import com.coredisc.domain.common.enums.EmailRequestType;
import com.coredisc.domain.member.MemberRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("!local")
public class MailServiceImpl implements MailService {

    private final RedisUtil redisUtil;

    private final JavaMailSender javaMailSender;

    private final MemberRepository memberRepository;

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Value("${spring.mail.auth-code-expiration-millis}")
    private Long codeExpiration;

    // 인증 코드 생성
    private String createCode() {

        int leftLimit = 48; // 0
        int rightLimit = 122; // z
        int targetStringLength = 6;
        Random random = new Random();

        return random.ints(leftLimit, rightLimit + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 | i >= 97))
                .limit(targetStringLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    // 이메일 폼 생성
    private MimeMessage createEmailForm(String email, String code, EmailRequestType emailRequestType) throws MessagingException {

        MimeMessage message = javaMailSender.createMimeMessage();

        message.setFrom(senderEmail);
        message.addRecipients(MimeMessage.RecipientType.TO, email);

        String body = "";
        switch (emailRequestType) {
            case SIGNUP:
                message.setSubject("CoreDisc 회원가입 이메일 인증 코드 안내");
                body += "<h3>회원가입을 위한 이메일 인증을 진행합니다.</h3>";
                break;
            case RESET_PASSWORD:
                message.setSubject("CoreDisc 비밀번호 변경 이메일 인증 코드 안내");
                body += "<h3>비밀번호 변경을 위한 이메일 인증을 진행합니다.</h3>";
                break;
            default:
                throw new AuthHandler(ErrorStatus.UNSUPPORTED_EMAIL_REQUEST_TYPE);
        }

        body += "<hr/>";
        body += "<p>아래 발급된 인증번호를 입력하여 인증을 완료해주세요.</p>";
        body += "<br>";
        body += "<p>인증번호 ";
        body += "<b>" + code + "</b></p>";
        message.setText(body, "UTF-8", "html");

        // Redis에 해당 인증코드 인증 시간 설정(10분)
        String redisKey = "auth:" + email + ":" + emailRequestType; // SIGNUP or RESET_PASSWORD 구분
        redisUtil.set(redisKey, code);
        redisUtil.expire(redisKey, codeExpiration, TimeUnit.MILLISECONDS);

        return message;
    }

    // 메일 발송
    @Async
    @Override
    public void sendEmail(String sendEmail, EmailRequestType emailRequestType) throws MessagingException {

        if(emailRequestType.equals(EmailRequestType.RESET_PASSWORD)) {
            memberRepository.findByEmail(sendEmail)
                    .orElseThrow(() -> new AuthHandler(ErrorStatus.MEMBER_NOT_FOUND));
        }

        String redisKey = "auth:" + sendEmail + ":" + emailRequestType;
        if (redisUtil.exists(redisKey)) {
            redisUtil.delete(redisKey);
        }

        String authCode = createCode();
        try {
            // 메일 생성
            MimeMessage message = createEmailForm(sendEmail, authCode, emailRequestType);
            // 메일 발송
            javaMailSender.send(message);
        } catch (MessagingException | MailSendException e) {
            log.error(e.getMessage());
            throw new AuthHandler(ErrorStatus.EMAIL_SEND_FAILED);
        }
    }
}
