package com.personal.baton.adapter.out.external.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrustedHttpOriginTest {

    @Test
    @DisplayName("HTTP origin은 scheme과 host를 정규화하고 명시한 포트를 보존한다")
    void normalizesOriginAndPreservesExplicitPort() {
        TrustedHttpOrigin origin = TrustedHttpOrigin.from(
                URI.create("HTTPS://ROUND.EXAMPLE:8443/")
        );

        assertThat(origin.uri()).isEqualTo(URI.create("https://round.example:8443"));
        assertThat(origin.scheme()).isEqualTo("https");
        assertThat(origin.host()).isEqualTo("round.example");
        assertThat(origin.port()).isEqualTo(8443);
        assertThat(origin.withPath("/room/abcd-efgh-jkmn"))
                .isEqualTo(URI.create(
                        "https://round.example:8443/room/abcd-efgh-jkmn"
                ));
    }

    @Test
    @DisplayName("IPv6 literal은 비교용 host와 렌더링용 대괄호를 각각 정규화한다")
    void normalizesIpv6LiteralHost() {
        TrustedHttpOrigin origin = TrustedHttpOrigin.from(
                URI.create("http://[::1]:5174/")
        );

        assertThat(origin.host()).isEqualTo("::1");
        assertThat(origin.uri()).isEqualTo(URI.create("http://[::1]:5174"));
        assertThat(origin.hasSameOriginAs(
                URI.create("http://[::1]:5174/room/abcd-efgh-jkmn")
        )).isTrue();
        assertThat(origin.withPath("/room/abcd-efgh-jkmn"))
                .isEqualTo(URI.create(
                        "http://[::1]:5174/room/abcd-efgh-jkmn"
                ));
    }

    @Test
    @DisplayName("생략한 기본 포트와 명시한 기본 포트는 같은 origin으로 비교한다")
    void treatsDefaultPortsAsEquivalent() {
        TrustedHttpOrigin implicitHttps = TrustedHttpOrigin.from(
                URI.create("https://round.example")
        );
        TrustedHttpOrigin explicitHttps = TrustedHttpOrigin.from(
                URI.create("https://round.example:443/")
        );
        TrustedHttpOrigin implicitHttp = TrustedHttpOrigin.from(
                URI.create("http://round.example")
        );

        assertThat(implicitHttps).isEqualTo(explicitHttps);
        assertThat(implicitHttps.hashCode()).isEqualTo(explicitHttps.hashCode());
        assertThat(implicitHttps.hasSameOriginAs(
                URI.create("HTTPS://ROUND.EXAMPLE:443/room/abcd-efgh-jkmn")
        )).isTrue();
        assertThat(explicitHttps.hasSameOriginAs(
                URI.create("https://round.example/room/abcd-efgh-jkmn")
        )).isTrue();
        assertThat(implicitHttp.hasSameOriginAs(
                URI.create("http://round.example:80/room/abcd-efgh-jkmn")
        )).isTrue();
        assertThat(implicitHttps.hasSameOriginAs(
                URI.create("https://round.example:8443/room/abcd-efgh-jkmn")
        )).isFalse();
        assertThat(implicitHttps.hasSameOriginAs(
                URI.create("http://round.example:443/room/abcd-efgh-jkmn")
        )).isFalse();
    }

    @Test
    @DisplayName("origin이 아닌 URI shape은 신뢰 설정으로 만들 수 없다")
    void rejectsNonOriginShapes() {
        assertThatThrownBy(() -> TrustedHttpOrigin.from(null))
                .isInstanceOf(IllegalArgumentException.class);
        for (String candidate : List.of(
                "/relative",
                "ftp://round.example",
                "https://user@round.example",
                "https://round.example/room",
                "https://round.example?ticket=secret",
                "https://round.example#fragment"
        )) {
            assertThatThrownBy(() -> TrustedHttpOrigin.from(URI.create(candidate)))
                    .as(candidate)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("origin 경로 조립은 authority를 바꾸거나 query와 fragment를 넣지 못한다")
    void rejectsUnsafeResolvedPaths() {
        TrustedHttpOrigin origin = TrustedHttpOrigin.from(
                URI.create("https://round.example")
        );

        for (String path : List.of(
                "relative",
                "//attacker.example/room",
                "/room/abcd?ticket=secret",
                "/room/abcd#fragment"
        )) {
            assertThatThrownBy(() -> origin.withPath(path))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> origin.withPath(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
