package com.personal.baton.application.round.error;

public class RoundGrantOperationException extends RuntimeException {

    private final String code;

    public RoundGrantOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RoundGrantOperationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
