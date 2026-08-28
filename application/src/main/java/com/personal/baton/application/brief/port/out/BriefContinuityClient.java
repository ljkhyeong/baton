package com.personal.baton.application.brief.port.out;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import java.util.Objects;

public interface BriefContinuityClient {

    DeliveryResult deliver(BriefContinuityDelivery delivery);

    enum Outcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record DeliveryResult(Outcome outcome, String code) {

        public DeliveryResult {
            Objects.requireNonNull(outcome, "BRIEF 전달 결과는 필수입니다");
        }

        public static DeliveryResult delivered(String code) {
            return new DeliveryResult(Outcome.DELIVERED, code);
        }

        public static DeliveryResult retryable(String code) {
            return new DeliveryResult(Outcome.RETRYABLE_FAILURE, code);
        }

        public static DeliveryResult permanentFailure(String code) {
            return new DeliveryResult(Outcome.PERMANENT_FAILURE, code);
        }
    }
}
