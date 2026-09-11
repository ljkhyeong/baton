package com.personal.baton.policy.watch;

import com.personal.baton.application.watch.WatchMonitorSource;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class WatchMonitorSourceTest {

    @DisplayName("최대 길이 네임스페이스도 WATCH의 128자 자료 참조 제한을 지킨다")
    @Test
    void keepsResourceReferenceWithinWatchLimit() {
        WatchMonitorSource source = new WatchMonitorSource("a".repeat(63));

        String reference = source.resourceReference(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        ));

        assertThat(reference).hasSize(128);
        assertThat(reference).matches("[A-Za-z0-9._:-]+");
    }

    @DisplayName("WATCH 제한을 넘는 네임스페이스는 시작 전에 거부한다")
    @Test
    void rejectsOversizedNamespace() {
        assertThatThrownBy(() -> new WatchMonitorSource("a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~63자");
    }

    @DisplayName("현재 네임스페이스의 표준 역할 자료 참조에서 UUID를 복원한다")
    @Test
    void parsesCanonicalRoleResourceReference() {
        WatchMonitorSource source = new WatchMonitorSource("study-pilot");
        UUID resourceId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        assertThat(source.resourceId(source.resourceReference(resourceId)))
                .contains(resourceId);
    }

    @DisplayName("다른 네임스페이스나 비표준 UUID 자료 참조는 소유하지 않는다")
    @Test
    void rejectsForeignOrNonCanonicalResourceReference() {
        WatchMonitorSource source = new WatchMonitorSource("study-pilot");

        assertThat(source.resourceId(
                "baton-manager:other:role-resource:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )).isEmpty();
        assertThat(source.resourceId(
                "baton-manager:study-pilot:role-resource:AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        )).isEmpty();
        assertThat(source.resourceId(
                "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa-extra"
        )).isEmpty();
    }
}
