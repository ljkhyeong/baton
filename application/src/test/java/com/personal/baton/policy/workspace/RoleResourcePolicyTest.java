package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class RoleResourcePolicyTest {

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
