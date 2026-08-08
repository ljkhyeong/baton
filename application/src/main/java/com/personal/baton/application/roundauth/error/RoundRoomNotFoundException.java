package com.personal.baton.application.roundauth.error;

public class RoundRoomNotFoundException extends RuntimeException {

    public RoundRoomNotFoundException() {
        super("ROUND 방을 찾을 수 없습니다");
    }
}
