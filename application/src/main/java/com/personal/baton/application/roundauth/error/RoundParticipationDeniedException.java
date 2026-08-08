package com.personal.baton.application.roundauth.error;

public class RoundParticipationDeniedException extends RuntimeException {

    public RoundParticipationDeniedException() {
        super("현재 계정에는 이 ROUND 방에 참여할 권한이 없습니다");
    }
}
