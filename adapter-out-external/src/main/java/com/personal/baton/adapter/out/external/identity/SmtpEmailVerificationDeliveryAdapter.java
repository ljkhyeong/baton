package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Objects;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.web.util.UriComponentsBuilder;

public final class SmtpEmailVerificationDeliveryAdapter
        implements EmailVerificationDeliveryPort {

    private final MailSender mailSender;
    private final String fromAddress;
    private final URI publicOrigin;

    SmtpEmailVerificationDeliveryAdapter(
            MailSender mailSender,
            String fromAddress,
            URI publicOrigin
    ) {
        this.mailSender = mailSender;
        this.fromAddress = validateAddress(fromAddress);
        this.publicOrigin = publicOrigin;
    }

    @Override
    public void deliver(EmailVerificationDelivery delivery) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(validateAddress(delivery.email()));
        message.setSubject("BATON 자체 이메일 계정을 인증해 주세요");
        message.setText("""
                BATON 자체 이메일 계정 가입을 계속하려면 아래 주소를 여세요.

                링크에서 이메일을 확인하고 비밀번호를 설정해야 가입이 완료됩니다.
                이 링크를 사용하기 전에는 비밀번호가 설정되지 않습니다.

                %s

                인증 링크는 %s까지 유효합니다. 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(verificationUrl(delivery.verificationToken()), delivery.expiresAt()));
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new EmailVerificationDeliveryUnavailableException(
                    "이메일 인증 메일을 발송하지 못했습니다",
                    exception
            );
        }
    }

    private String verificationUrl(String token) {
        return UriComponentsBuilder.fromUri(publicOrigin)
                .pathSegment("verify-email")
                .fragment("token={token}")
                .buildAndExpand(Objects.requireNonNull(token, "인증 토큰은 필수입니다"))
                .encode()
                .toUriString();
    }

    private static String validateAddress(String address) {
        try {
            InternetAddress parsed = new InternetAddress(
                    Objects.requireNonNull(address, "이메일 주소는 필수입니다"),
                    true
            );
            return parsed.getAddress();
        } catch (AddressException exception) {
            throw new IllegalArgumentException("유효한 이메일 주소가 필요합니다", exception);
        }
    }
}
