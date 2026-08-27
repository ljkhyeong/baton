package com.personal.baton.application.workspace.error;

public class SeasonNameConflictException extends RuntimeException {

    private static final String MESSAGE = "같은 팀에 동일한 이름의 시즌이 이미 있습니다";

    public SeasonNameConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
