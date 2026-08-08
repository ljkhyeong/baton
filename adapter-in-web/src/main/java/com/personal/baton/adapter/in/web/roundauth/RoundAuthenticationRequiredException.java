package com.personal.baton.adapter.in.web.roundauth;

final class RoundAuthenticationRequiredException extends RuntimeException {

    RoundAuthenticationRequiredException() {
        super("BATON 계정 로그인이 필요합니다");
    }
}
