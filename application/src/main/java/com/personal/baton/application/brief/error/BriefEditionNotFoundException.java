package com.personal.baton.application.brief.error;

public class BriefEditionNotFoundException extends RuntimeException {

    public BriefEditionNotFoundException() {
        super("생성된 BRIEF 에디션을 찾을 수 없습니다");
    }
}

