package com.personal.baton.application.identity.error;

public class IdentityConflictException extends RuntimeException {

    public IdentityConflictException(String message) {
        super(message);
    }

    public IdentityConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
