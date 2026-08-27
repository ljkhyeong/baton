package com.personal.baton.application.watch.error;

public class WatchHealthEventChangedAtOutOfRangeException extends IllegalArgumentException {

    public WatchHealthEventChangedAtOutOfRangeException() {
        super("WATCH changedAt은 UTC 기준 1000년 이상 10000년 미만이어야 합니다");
    }
}
