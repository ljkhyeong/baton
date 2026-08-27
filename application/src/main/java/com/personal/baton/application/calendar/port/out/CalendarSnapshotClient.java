package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSnapshot;
import java.util.Objects;

public interface CalendarSnapshotClient {

    DeliveryResult deliver(CalendarSnapshot snapshot);

    enum Outcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    record DeliveryResult(Outcome outcome, String code) {

        public DeliveryResult {
            Objects.requireNonNull(outcome, "CAL 전달 결과는 필수입니다");
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
