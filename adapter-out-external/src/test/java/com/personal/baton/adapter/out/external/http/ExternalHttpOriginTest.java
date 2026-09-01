package com.personal.baton.adapter.out.external.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExternalHttpOriginTest {

    @Test
    @DisplayName("HTTPS origin에 빈 명시 포트가 있으면 설정을 거부한다")
    void rejectEmptyExplicitPort() {
        assertThatThrownBy(() -> ExternalHttpOrigin.requireHttps(
                "외부 서비스 주소",
                "https://example.com:"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("외부 서비스 주소의 명시 포트는 1~65535 범위여야 합니다");
    }
}
