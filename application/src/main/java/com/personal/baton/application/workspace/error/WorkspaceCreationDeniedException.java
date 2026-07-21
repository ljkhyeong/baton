package com.personal.baton.application.workspace.error;

public class WorkspaceCreationDeniedException extends RuntimeException {

    public WorkspaceCreationDeniedException() {
        super("워크스페이스를 생성할 권한이 없습니다");
    }
}
