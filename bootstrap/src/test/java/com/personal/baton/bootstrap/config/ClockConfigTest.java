package com.personal.baton.bootstrap.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @DisplayName("운영 Clock은 MySQL 저장 정밀도에 맞춰 UTC instant를 마이크로초로 제한한다")
    @Test
    void limitsProductionClockToDatabasePrecision() {
        Instant sourceInstant = Instant.parse("2026-08-01T12:34:56.123456789Z");

        Clock clock = ClockConfig.databaseCompatibleClock(
                Clock.fixed(sourceInstant, ZoneOffset.UTC)
        );

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-01T12:34:56.123456Z"));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
