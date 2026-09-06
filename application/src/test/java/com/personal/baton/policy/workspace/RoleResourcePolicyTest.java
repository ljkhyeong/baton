package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class RoleResourcePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://i.ytimg.com/image.jpg", "https://i.ytimg.com.evil.test/image.jpg",
            "https://user:password@i.ytimg.com/image.jpg", "https://i.vimeocdn.com:8443/image.jpg", "https://127.0.0.1/image.jpg"})
    @DisplayName("자료 저장은 허용된 영상 공급자 밖의 썸네일을 거부한다")
    void rejectsUnsafeThumbnail(String url) {
        var resource = RoleResource.create(UUID.randomUUID(), UUID.randomUUID(), "안내 영상",
                "https://youtu.be/dQw4w9WgXcQ", null, Instant.parse("2026-07-20T01:02:03Z"));
        assertThatThrownBy(() -> resource.updateThumbnail(url)).isInstanceOf(DomainValidationException.class);
        assertThat(resource.getThumbnailUrl()).isNull();
    }


    @DisplayName("국제화 도메인의 역할 자료 URL은 원문을 보존한다")
    @Test
    void preservesInternationalizedDomainUrl() {
        String internationalizedUrl = "https://한글.kr/스터디/운영-가이드";

        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "운영 가이드",
                internationalizedUrl,
                null,
                Instant.parse("2026-07-20T01:02:03Z")
        );

        assertThat(resource.getUrl()).isEqualTo(internationalizedUrl);
    }

    @DisplayName("국제화 도메인에도 사용자 정보가 포함된 역할 자료 URL은 거부한다")
    @Test
    void rejectsUserInfoFromInternationalizedDomainUrl() {
        assertThatThrownBy(() -> RoleResource.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "비공개 자료",
                "https://user:secret@한글.kr/private",
                null,
                Instant.parse("2026-07-20T01:02:03Z")
        )).isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("숫자가 아니거나 범위를 벗어난 명시 포트의 역할 자료 URL은 거부한다")
    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com:-1/private",
            "https://한글.kr:65536/private",
            "https://한글.kr:not-a-port/private",
            "https://한글.kr:٨٠/private",
            "https://example.com:１２３/private"
    })
    void rejectsInvalidExplicitPorts(String url) {
        assertThatThrownBy(() -> RoleResource.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "잘못된 포트 자료",
                url,
                null,
                Instant.parse("2026-07-20T01:02:03Z")
        )).isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("유효 범위의 명시 포트와 IPv6 역할 자료 URL은 허용한다")
    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com:0/guide",
            "https://example.com:65535/guide",
            "https://한글.kr:8443/guide",
            "http://[::1]:8080/guide"
    })
    void acceptsValidExplicitPorts(String url) {
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "유효한 포트 자료",
                url,
                null,
                Instant.parse("2026-07-20T01:02:03Z")
        );

        assertThat(resource.getUrl()).isEqualTo(url);
    }

    @DisplayName("역할 자료 수정 검증에 실패하면 기존 값을 그대로 보존한다")
    @Test
    void preservesExistingValuesWhenUpdateValidationFails() {
        UUID originalRoleId = UUID.randomUUID();
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                originalRoleId,
                "질문 정리 가이드",
                "https://docs.example.com/questions",
                "질문 분류 기준",
                Instant.parse("2026-07-20T01:02:03Z")
        );

        assertThatThrownBy(() -> resource.update(
                UUID.randomUUID(),
                "바뀐 제목",
                "file:///etc/passwd",
                "바뀐 설명"
        )).isInstanceOf(DomainValidationException.class);

        assertThat(resource.getRoleId()).isEqualTo(originalRoleId);
        assertThat(resource.getTitle()).isEqualTo("질문 정리 가이드");
        assertThat(resource.getUrl()).isEqualTo("https://docs.example.com/questions");
        assertThat(resource.getDescription()).isEqualTo("질문 분류 기준");
    }
}
