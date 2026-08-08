package com.personal.baton.policy.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("policy")
class RoundRoomTombstonePolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T10:00:00Z");

    @Test
    @DisplayName("ROUND 방은 생성 시각보다 앞선 시각에 종료할 수 없다")
    void rejectsEndingBeforeCreation() {
        RoundRoomTombstone tombstone = tombstone();

        assertThatIllegalArgumentException().isThrownBy(
                () -> tombstone.end(CREATED_AT.minusNanos(1))
        );
        assertThat(tombstone.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("ROUND 방은 생성 시각과 같은 경계에서 종료할 수 있다")
    void allowsEndingAtCreationBoundary() {
        RoundRoomTombstone tombstone = tombstone();

        tombstone.end(CREATED_AT);

        assertThat(tombstone.getEndedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("ROUND 방은 생성 시각 뒤에 종료하며 반복 종료에도 최초 시각을 유지한다")
    void preservesFirstEndingAfterCreation() {
        RoundRoomTombstone tombstone = tombstone();
        Instant firstEndedAt = CREATED_AT.plusSeconds(1);

        tombstone.end(firstEndedAt);
        tombstone.end(firstEndedAt.plusSeconds(1));

        assertThat(tombstone.getEndedAt()).isEqualTo(firstEndedAt);
    }

    private RoundRoomTombstone tombstone() {
        return RoundRoomTombstone.create(
                "abcd-efgh-jkmn",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CREATED_AT
        );
    }
}
