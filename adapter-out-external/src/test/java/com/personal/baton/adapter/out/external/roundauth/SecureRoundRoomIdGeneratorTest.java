package com.personal.baton.adapter.out.external.roundauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.domain.roundauth.RoundRoomId;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecureRoundRoomIdGeneratorTest {

    @Test
    @DisplayName("보안 난수의 12개 문자를 canonical 4-4-4 ROUND 방 식별자로 만든다")
    void generateCanonicalRoomId() {
        SecureRandom secureRandom = new SecureRandom() {
            private int nextIndex;

            @Override
            public int nextInt(int bound) {
                assertThat(bound).isEqualTo(31);
                return nextIndex++;
            }
        };
        SecureRoundRoomIdGenerator generator = new SecureRoundRoomIdGenerator(secureRandom);

        String roomId = generator.generate();

        assertThat(roomId).isEqualTo("abcd-efgh-jkmn");
        assertThat(RoundRoomId.isCanonical(roomId)).isTrue();
    }

    @Test
    @DisplayName("실제 보안 난수 생성기는 반복 발급에서도 canonical 식별자를 생성한다")
    void generateDistinctCanonicalRoomIds() {
        SecureRoundRoomIdGenerator generator = new SecureRoundRoomIdGenerator();
        Set<String> roomIds = new HashSet<>();

        for (int count = 0; count < 1_000; count++) {
            String roomId = generator.generate();
            assertThat(RoundRoomId.isCanonical(roomId)).isTrue();
            roomIds.add(roomId);
        }

        assertThat(roomIds).hasSize(1_000);
    }
}
