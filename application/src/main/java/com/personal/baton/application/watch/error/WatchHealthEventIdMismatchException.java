package com.personal.baton.application.watch.error;

public class WatchHealthEventIdMismatchException extends RuntimeException {

    public WatchHealthEventIdMismatchException() {
        super("Idempotency-Key와 WATCH eventId가 일치하지 않습니다");
    }
}
