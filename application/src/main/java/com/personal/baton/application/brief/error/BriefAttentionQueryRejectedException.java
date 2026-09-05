package com.personal.baton.application.brief.error;

public class BriefAttentionQueryRejectedException extends RuntimeException {
    public BriefAttentionQueryRejectedException() {
        super("관심 항목 조회 조건이 올바르지 않습니다. 첫 페이지부터 다시 조회해 주세요");
    }
}
