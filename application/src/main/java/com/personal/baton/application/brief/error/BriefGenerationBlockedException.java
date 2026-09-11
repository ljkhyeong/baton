package com.personal.baton.application.brief.error;

public class BriefGenerationBlockedException extends RuntimeException {

    public BriefGenerationBlockedException() {
        super("BRIEF 이벤트 전달이 끝난 뒤 주간 요약을 만들 수 있습니다");
    }
}

