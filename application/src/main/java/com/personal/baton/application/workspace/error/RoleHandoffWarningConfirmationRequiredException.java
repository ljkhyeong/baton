package com.personal.baton.application.workspace.error;

public class RoleHandoffWarningConfirmationRequiredException extends RuntimeException {

    public RoleHandoffWarningConfirmationRequiredException() {
        super("미완료 인수인계 항목이나 연결 자료 없음 경고를 확인해 주세요");
    }
}
