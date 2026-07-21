package com.personal.baton.application.workspace.error;

public class WorkspaceContentConflictException extends RuntimeException {

    public WorkspaceContentConflictException() {
        super("다른 사용자가 먼저 내용을 변경했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요");
    }
}
