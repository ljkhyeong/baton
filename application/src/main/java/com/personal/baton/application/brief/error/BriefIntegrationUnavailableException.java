package com.personal.baton.application.brief.error;

public class BriefIntegrationUnavailableException extends RuntimeException {

    public BriefIntegrationUnavailableException() {
        super("BRIEF 서비스를 현재 사용할 수 없습니다");
    }
}

