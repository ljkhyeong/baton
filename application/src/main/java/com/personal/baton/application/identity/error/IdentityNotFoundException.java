package com.personal.baton.application.identity.error;

public class IdentityNotFoundException extends RuntimeException {

    private final String code;

    public IdentityNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
