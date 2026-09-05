package com.personal.baton.application.brief.error;

public class BriefAccessDeniedException extends RuntimeException {

    public BriefAccessDeniedException() {
        super("활동 중인 팀 구성원만 BRIEF를 볼 수 있습니다.");
    }
}

