package com.personal.baton.application.brief.error;

public class BriefGenerationBlockedException extends RuntimeException {

    public BriefGenerationBlockedException() {
        super("BRIEF 이벤트 전달이 완료된 뒤 에디션을 생성할 수 있습니다");
    }
}

