package com.personal.baton.adapter.out.external.roundauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.domain.roundauth.RoundRoomId;
import java.security.SecureRandom;
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
        assertThat(new RoundRoomId(roomId).value()).isEqualTo(roomId);
    }
}
