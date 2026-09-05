package com.personal.baton.application.brief.error;

public class BriefGenerationInProgressException extends RuntimeException {

    public BriefGenerationInProgressException() {
        super("해당 주의 브리프를 생성 중입니다.");
    }
}

