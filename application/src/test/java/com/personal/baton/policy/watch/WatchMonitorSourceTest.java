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

    @DisplayName("최대 길이 namespace도 WATCH의 128자 resource reference 제한을 지킨다")
    @Test
    void keepsResourceReferenceWithinWatchLimit() {
        WatchMonitorSource source = new WatchMonitorSource("a".repeat(63));

        String reference = source.resourceReference(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        ));

        assertThat(reference).hasSize(128);
        assertThat(reference).matches("[A-Za-z0-9._:-]+");
    }

    @DisplayName("WATCH 제한을 넘는 namespace는 시작 전에 거절한다")
    @Test
    void rejectsOversizedNamespace() {
        assertThatThrownBy(() -> new WatchMonitorSource("a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~63자");
    }
}
