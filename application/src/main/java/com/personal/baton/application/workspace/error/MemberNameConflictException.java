package com.personal.baton.application.workspace.error;

public class MemberNameConflictException extends RuntimeException {

    public MemberNameConflictException() {
        super("같은 팀에 동일한 이름의 구성원이 이미 있습니다");
    }
}
