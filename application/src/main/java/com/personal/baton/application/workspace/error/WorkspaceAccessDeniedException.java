package com.personal.baton.application.workspace.error;

public class WorkspaceAccessDeniedException extends RuntimeException {

    public WorkspaceAccessDeniedException() {
        super("작업 공간 접근 키가 올바르지 않습니다");
    }
}
