package com.personal.baton.application.watch.error;

public class WatchHealthEventConflictException extends RuntimeException {

    public WatchHealthEventConflictException() {
        super("WATCH eventId가 기존 이벤트와 충돌합니다");
    }
}
