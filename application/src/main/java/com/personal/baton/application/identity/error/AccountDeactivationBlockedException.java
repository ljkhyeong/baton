package com.personal.baton.application.identity.error;

public class AccountDeactivationBlockedException extends RuntimeException {
    public AccountDeactivationBlockedException(String teamName) {
        super(teamName + " 팀에서 다른 활성 관리자를 지정한 뒤 계정을 비활성화해 주세요");
    }
}
