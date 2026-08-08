package com.personal.baton.application.roundauth.error;

public class AccountMembershipConflictException extends RuntimeException {

    public AccountMembershipConflictException(String message) {
        super(message);
    }

    public AccountMembershipConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
