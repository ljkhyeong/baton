package com.personal.baton.adapter.out.external.roundauth;

import com.personal.baton.application.roundauth.port.out.RoundRoomIdGenerator;
import java.security.SecureRandom;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SecureRoundRoomIdGenerator implements RoundRoomIdGenerator {

    private static final char[] ALPHABET =
            "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int RANDOM_CHARACTER_COUNT = 12;

    private final SecureRandom secureRandom;

    public SecureRoundRoomIdGenerator() {
        this(new SecureRandom());
    }

    SecureRoundRoomIdGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "보안 난수 생성기는 필수입니다");
    }

    @Override
    public String generate() {
        StringBuilder roomId = new StringBuilder(14);
        for (int index = 0; index < RANDOM_CHARACTER_COUNT; index++) {
            if (index == 4 || index == 8) {
                roomId.append('-');
            }
            roomId.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return roomId.toString();
    }
}
