package com.personal.baton.application.workspace.error;

public class WorkspaceAccessKeyConflictException extends RuntimeException {

    private static final String MESSAGE =
            "접근 키가 동시에 변경되었습니다. 최신 키로 다시 시도해 주세요";

    public WorkspaceAccessKeyConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
