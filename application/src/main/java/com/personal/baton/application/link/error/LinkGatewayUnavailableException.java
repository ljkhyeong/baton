package com.personal.baton.application.link.error;

public class LinkGatewayUnavailableException extends RuntimeException {

    public static final String CODE = "LINK_GATEWAY_UNAVAILABLE";

    public LinkGatewayUnavailableException() {
        super("링크 서비스를 일시적으로 사용할 수 없습니다");
    }

    public LinkGatewayUnavailableException(Throwable cause) {
        super("링크 서비스를 일시적으로 사용할 수 없습니다", cause);
    }
}
