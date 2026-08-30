package com.personal.baton.application.workspace.error;

public class WorkspaceRecoveryDeniedException extends RuntimeException {

    public WorkspaceRecoveryDeniedException() {
        super("운영자 복구 키가 없거나 올바르지 않습니다");
    }
}
