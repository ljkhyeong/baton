package com.personal.baton.application.watch.error;

public class WatchHealthEventResourceReferenceException extends RuntimeException {

    public WatchHealthEventResourceReferenceException() {
        super("WATCH resourceReference가 현재 BATON namespace와 일치하지 않습니다");
    }
}
