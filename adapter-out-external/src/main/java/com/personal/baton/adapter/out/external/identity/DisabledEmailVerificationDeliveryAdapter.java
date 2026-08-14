package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;

public final class DisabledEmailVerificationDeliveryAdapter
        implements EmailVerificationDeliveryPort {

    @Override
    public void deliver(EmailVerificationDelivery delivery) {
        throw new EmailVerificationDeliveryUnavailableException(
                "자체 이메일 가입용 인증 메일 발송기가 설정되지 않았습니다"
        );
    }
}
