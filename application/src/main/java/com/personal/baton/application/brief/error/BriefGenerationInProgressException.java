package com.personal.baton.application.brief.error;

public class BriefGenerationInProgressException extends RuntimeException {

    public BriefGenerationInProgressException() {
        super("같은 범위의 BRIEF 에디션 생성을 처리하고 있습니다");
    }
}

