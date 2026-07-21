package com.personal.baton.application.workspace.error;

public class SeasonRoundNameConflictException extends RuntimeException {

    public SeasonRoundNameConflictException() {
        super("같은 시즌에 동일한 회차 이름을 사용할 수 없습니다");
    }
}
