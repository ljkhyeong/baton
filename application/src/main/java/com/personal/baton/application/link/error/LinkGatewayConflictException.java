package com.personal.baton.application.link.error;

public class LinkGatewayConflictException extends RuntimeException {

    public static final String CODE = "LINK_GATEWAY_CONFLICT";

    public LinkGatewayConflictException() {
        super("같은 링크 요청이 다른 내용으로 이미 처리되었습니다");
    }

    public LinkGatewayConflictException(Throwable cause) {
        super("같은 링크 요청이 다른 내용으로 이미 처리되었습니다", cause);
    }
}
