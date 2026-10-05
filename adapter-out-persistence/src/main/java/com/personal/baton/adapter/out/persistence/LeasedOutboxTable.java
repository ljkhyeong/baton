package com.personal.baton.adapter.out.persistence;

import static com.personal.baton.adapter.out.persistence.JdbcTimestamps.utc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

// 처리 임대를 쓰는 BRIEF·CAL·WATCH 아웃박스의 공통 상태 전환이다.
// 선점 조회(FOR UPDATE SKIP LOCKED)와 행 변환은 각 어댑터가 맡고, 결과 코드는 애플리케이션이 정규화한다.
public final class LeasedOutboxTable {

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    public LeasedOutboxTable(JdbcTemplate jdbcTemplate, String table) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
    }

    public boolean lease(long id, UUID leaseToken, Instant leaseExpiresAt) {
        return jdbcTemplate.update(
                """
                UPDATE %s
                SET delivery_status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    lease_token = UUID_TO_BIN(?),
                    lease_expires_at = ?,
                    completed_at = NULL,
                    result_code = NULL
                WHERE id = ?
                """.formatted(table),
                requiredLeaseToken(leaseToken).toString(),
                utc(leaseExpiresAt),
                id
        ) == 1;
    }

    public boolean markDelivered(long id, UUID leaseToken, Instant deliveredAt, String resultCode) {
        return release(
                id,
                leaseToken,
                "delivery_status = 'DELIVERED', completed_at = ?, result_code = ?, last_error_code = NULL",
                utc(deliveredAt),
                resultCode
        );
    }

    public boolean markRetry(long id, UUID leaseToken, Instant availableAt, String errorCode) {
        return release(
                id,
                leaseToken,
                "delivery_status = 'PENDING', available_at = ?, completed_at = NULL, result_code = NULL, last_error_code = ?",
                utc(availableAt),
                errorCode
        );
    }

    public boolean markFailed(long id, UUID leaseToken, Instant failedAt, String errorCode) {
        return release(
                id,
                leaseToken,
                "delivery_status = 'FAILED', completed_at = ?, result_code = NULL, last_error_code = ?",
                utc(failedAt),
                errorCode
        );
    }

    // 현재 임대를 가진 작업자만 상태를 바꾼다. 임대가 넘어간 늦은 완료는 반영하지 않는다.
    private boolean release(long id, UUID leaseToken, String assignments, LocalDateTime at, String code) {
        return jdbcTemplate.update(
                """
                UPDATE %s
                SET %s,
                    lease_token = NULL,
                    lease_expires_at = NULL
                WHERE id = ?
                AND delivery_status = 'PROCESSING'
                AND lease_token = UUID_TO_BIN(?)
                """.formatted(table, assignments),
                at,
                code,
                id,
                requiredLeaseToken(leaseToken).toString()
        ) == 1;
    }

    private static UUID requiredLeaseToken(UUID leaseToken) {
        return Objects.requireNonNull(leaseToken, "아웃박스 처리 임대 토큰은 필수입니다");
    }
}
