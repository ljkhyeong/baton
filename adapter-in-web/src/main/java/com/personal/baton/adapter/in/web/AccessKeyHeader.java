package com.personal.baton.adapter.in.web;

// 계정 접근을 쓰지 않는 팀이 요청마다 보내는 공유 접근 키 헤더다.
public final class AccessKeyHeader {

    public static final String NAME = "X-Baton-Access-Key";

    private AccessKeyHeader() {
    }
}
