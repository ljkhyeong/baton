package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import com.personal.baton.domain.identity.EmailChallengePurpose;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Objects;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.UriComponentsBuilder;

public final class SmtpEmailVerificationDeliveryAdapter
        implements EmailVerificationDeliveryPort {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final URI publicOrigin;

    SmtpEmailVerificationDeliveryAdapter(
            JavaMailSender mailSender,
            String fromAddress,
            URI publicOrigin
    ) {
        this.mailSender = mailSender;
        this.fromAddress = validateAddress(fromAddress);
        this.publicOrigin = publicOrigin;
    }

    @Override
    public void deliver(EmailVerificationDelivery delivery) {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        try {
            MimeMessageHelper message = new MimeMessageHelper(mimeMessage, "UTF-8");
            message.setFrom(fromAddress);
            message.setTo(validateAddress(delivery.email()));
            mimeMessage.setHeader("X-Mailin-custom", "baton-delivery-id:" + delivery.deliveryId());
            boolean passwordReset = delivery.purpose() == EmailChallengePurpose.PASSWORD_RESET;
            message.setSubject(passwordReset ? "BATON 비밀번호를 재설정해 주세요" : "BATON 자체 이메일 계정을 인증해 주세요");
            String body = passwordReset ? """
                    BATON 비밀번호를 재설정하려면 아래 주소를 여세요.

                    새 비밀번호를 저장하면 기존 BATON 계정 로그인 세션이 모두 종료됩니다.
                    변경한 비밀번호로 다시 로그인해 주세요.

                    %s

                    재설정 링크는 %s까지 유효하며 한 번만 사용할 수 있습니다.
                    요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 변경되지 않습니다.
                    """ : """
                    BATON 자체 이메일 계정 가입을 계속하려면 아래 주소를 여세요.

                    링크에서 이메일을 확인하고 비밀번호를 설정해야 가입이 완료됩니다.
                    이 링크를 사용하기 전에는 비밀번호가 설정되지 않습니다.

                    %s

                    인증 링크는 %s까지 유효합니다. 요청하지 않았다면 이 메일을 무시하세요.
                    """;
            message.setText(body.formatted(
                    verificationUrl(delivery.verificationToken(), passwordReset ? "reset-password" : "verify-email"),
                    delivery.expiresAt()));
            mailSender.send(mimeMessage);
        } catch (MailException | MessagingException exception) {
            throw new EmailVerificationDeliveryUnavailableException(
                    "이메일 인증 메일을 발송하지 못했습니다",
                    exception
            );
        }
    }

    private String verificationUrl(String token, String path) {
        return UriComponentsBuilder.fromUri(publicOrigin)
                .pathSegment(path)
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
