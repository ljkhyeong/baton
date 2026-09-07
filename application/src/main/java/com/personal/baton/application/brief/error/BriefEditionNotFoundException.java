package com.personal.baton.application.brief.error;

public class BriefEditionNotFoundException extends RuntimeException {

    public BriefEditionNotFoundException() {
        super("저장된 주간 요약을 찾을 수 없습니다.");
    }
}
