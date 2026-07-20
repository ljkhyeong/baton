package com.personal.baton.application.workspace.error;

public class RoleNameConflictException extends RuntimeException {

    public RoleNameConflictException() {
        super("같은 팀에 동일한 이름의 역할이 이미 있습니다");
    }
}
