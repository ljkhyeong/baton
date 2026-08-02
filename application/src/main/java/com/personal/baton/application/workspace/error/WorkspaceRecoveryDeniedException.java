package com.personal.baton.application.workspace.error;

public class WorkspaceRecoveryDeniedException extends RuntimeException {

    public WorkspaceRecoveryDeniedException() {
        super("워크스페이스 접근 키를 복구할 권한이 없습니다");
    }
}
