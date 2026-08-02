package com.personal.baton.application.workspace.error;

public class SeasonEndedException extends RuntimeException {

    public SeasonEndedException() {
        super("종료된 시즌의 내용은 변경할 수 없습니다");
    }
}
