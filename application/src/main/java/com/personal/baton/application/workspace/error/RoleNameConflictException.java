package com.personal.baton.application.workspace.error;

public class RoleNameConflictException extends RuntimeException {

    private static final String MESSAGE = "같은 시즌에 동일한 이름의 역할이 이미 있습니다";

    public RoleNameConflictException() {
        super(MESSAGE);
    }

    public RoleNameConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
