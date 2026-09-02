package com.personal.baton.application.calendar.port.in;

import java.util.UUID;

public interface CompleteCalendarRecoveryUseCase {

    Result complete(UUID recoveryId);

    record Result(Status status, int seasonCount, String code) {
        public enum Status {
            WAITING,
            COMPLETED,
            FAILED
        }

        public static Result waiting(String code) {
            return new Result(Status.WAITING, 0, code);
        }

        public static Result completed(int seasonCount) {
            return new Result(Status.COMPLETED, seasonCount, null);
        }

        public static Result failed(String code) {
            return new Result(Status.FAILED, 0, code);
        }
    }
}
