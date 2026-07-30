package com.personal.baton.application.identity.error;

public class IdentityOperationException extends RuntimeException {

    private final String code;

    public IdentityOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public IdentityOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
