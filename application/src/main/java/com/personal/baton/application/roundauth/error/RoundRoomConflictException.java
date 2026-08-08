package com.personal.baton.application.roundauth.error;

public class RoundRoomConflictException extends RuntimeException {

    public RoundRoomConflictException(String message) {
        super(message);
    }

    public RoundRoomConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
