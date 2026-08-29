package com.personal.baton.application.brief.error;

public class BriefAccessDeniedException extends RuntimeException {

    public BriefAccessDeniedException() {
        super("BRIEF를 조회할 수 있는 활성 팀 멤버십이 필요합니다");
    }
}

