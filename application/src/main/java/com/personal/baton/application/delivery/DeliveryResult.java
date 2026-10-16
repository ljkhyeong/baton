package com.personal.baton.application.delivery;

import java.util.Objects;

// BRIEF·CAL 아웃박스 항목 하나를 외부 서비스에 전달한 결과와 그 결과 코드다.
public record DeliveryResult(Outcome outcome, String code) {

    public DeliveryResult {
        Objects.requireNonNull(outcome, "전달 결과는 필수입니다");
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

    public enum Outcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }
}
