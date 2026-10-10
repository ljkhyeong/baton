package com.personal.baton.adapter.in.web;

// 계정 세션 요청이 화면에서 확인한 로그인 계정 ID를 함께 보내는 헤더다.
public final class AccountIdHeader {

    public static final String NAME = "X-Baton-Account-Id";

    private AccountIdHeader() {
    }
}
