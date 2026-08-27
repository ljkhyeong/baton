package com.personal.baton.application.identity.error;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("계정을 찾을 수 없습니다");
    }
}
