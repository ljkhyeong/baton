package com.personal.baton.application.workspace.error;

public class SeasonSuccessorExistsException extends RuntimeException {

    private static final String MESSAGE = "이 시즌에서 시작한 다음 시즌이 이미 있습니다";

    public SeasonSuccessorExistsException() {
        super(MESSAGE);
    }

    public SeasonSuccessorExistsException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
