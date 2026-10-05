package com.personal.baton.adapter.out.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

// JDBC 어댑터는 DATETIME 열에 UTC 기준 LocalDateTime을 저장한다. 세션 시간대에 따라 값이 바뀌지 않게 한다.
public final class JdbcTimestamps {

    private JdbcTimestamps() {
    }

    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "저장할 시각은 필수입니다"), ZoneOffset.UTC);
    }
}
