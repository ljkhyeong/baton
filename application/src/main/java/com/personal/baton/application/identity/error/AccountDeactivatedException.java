package com.personal.baton.application.identity.error;

public class AccountDeactivatedException extends RuntimeException {
    public AccountDeactivatedException() { super("비활성화한 계정은 사용할 수 없습니다"); }
}
