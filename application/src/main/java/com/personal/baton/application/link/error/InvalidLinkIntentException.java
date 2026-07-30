package com.personal.baton.application.link.error;

public class InvalidLinkIntentException extends RuntimeException {

    private final String code;

    public InvalidLinkIntentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
