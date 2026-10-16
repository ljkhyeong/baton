package com.personal.baton.adapter.out.external.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalHttpOriginTest {

    private static final String SUBJECT = "외부 서비스 URL";

    @ParameterizedTest
    @ValueSource(ints = {1, 65_535})
    @DisplayName("HTTPS 출처의 명시 포트는 유효 범위 경곗값을 허용한다")
    void acceptValidExplicitPortBoundaries(int port) {
        assertThat(ExternalHttpOrigin.requireHttps(SUBJECT, "https://example.com:" + port).getPort())
                .isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com:0",
            "https://example.com:65536",
            "https://example.com:"
    })
    @DisplayName("HTTPS 출처의 명시 포트가 비었거나 유효 범위 밖이면 설정을 거부한다")
    void rejectInvalidExplicitPort(String value) {
        assertThatThrownBy(() -> ExternalHttpOrigin.requireHttps(SUBJECT, value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("외부 서비스 URL의 명시 포트는 1~65535 범위여야 합니다");
    }

    @Test
    @DisplayName("HTTPS 출처에 서비스 경로가 있으면 출처 혼동을 막기 위해 거부한다")
    void rejectPath() {
        assertThatThrownBy(() -> ExternalHttpOrigin.requireHttps(SUBJECT, "https://example.com/base"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("외부 서비스 URL은 경로, 사용자 정보, 쿼리, 조각이 없는 절대 HTTPS 출처여야 합니다");
    }
}
