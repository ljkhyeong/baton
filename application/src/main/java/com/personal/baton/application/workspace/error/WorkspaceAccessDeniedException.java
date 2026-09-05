package com.personal.baton.application.workspace.error;

public class WorkspaceAccessDeniedException extends RuntimeException {

    public WorkspaceAccessDeniedException() {
        super("작업 공간에 접근할 권한이 없습니다. 계정의 초대·권한 또는 공유 링크를 확인해 주세요");
    }
}
