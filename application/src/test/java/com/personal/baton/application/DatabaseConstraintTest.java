package com.personal.baton.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// 애플리케이션 검증을 거치지 않은 직접 쓰기에서도 기준 스키마가 막아야 하는 불변식만 확인한다.
// 각 테스트는 무작위 식별자로 자기 행을 만들어 실행 순서와 무관하게 동작한다.
@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseConstraintTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 8, 9, 0);
    private static final LocalDate OCCURRENCE_DATE = LocalDate.of(2026, 8, 10);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_database_constraint")
            .withUsername("baton")
            .withPassword("password");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
    }

    @DisplayName("구성원 이름은 팀 안에서 대소문자를 구분해 유일하고 다른 팀과는 겹칠 수 있다")
    @Test
    void memberNameIsUniqueWithinTeam() {
        String teamId = insertTeam();
        String otherTeamId = insertTeam();
        insertMember(teamId, "Alice");
        insertMember(teamId, "alice");
        insertMember(otherTeamId, "Alice");

        assertRejectedBy("uk_members_team_name", () -> insertMember(teamId, "Alice"));
    }

    @DisplayName("콘텐츠 생성 멱등 기록은 지원하는 작업 종류만 저장한다")
    @Test
    void contentIdempotencyAcceptsOnlySupportedOperations() {
        String teamId = insertTeam();
        String seasonId = insertSeason(teamId, "멱등 시즌", null);
        List.of("MEMBER", "ROLE_RESOURCE", "ROLE_HANDOFF")
                .forEach(operation -> insertIdempotency(teamId, seasonId, operation));

        assertRejectedBy(
                "chk_content_creation_idempotency_operation",
                () -> insertIdempotency(teamId, seasonId, "UNSUPPORTED")
        );
    }

    @DisplayName("팀마다 활성 시즌은 하나이고 후속 시즌과 루틴 담당 역할은 시즌 계보를 벗어날 수 없다")
    @Test
    void seasonLifecycleKeepsSingleActiveSeasonAndLineage() {
        String teamId = insertTeam();
        String summerId = insertSeason(teamId, "여름 시즌", null);
        String summerRoleId = insertRole(teamId, summerId);

        assertRejectedBy("uk_seasons_active_team", () -> insertSeason(teamId, "동시 활성 시즌", null));

        endSeason(summerId);
        String autumnId = insertSeason(teamId, "가을 시즌", summerId);
        insertRoutine(autumnId, insertRole(teamId, autumnId));
        assertRejectedBy("fk_routines_owner_role_season", () -> insertRoutine(autumnId, summerRoleId));

        endSeason(autumnId);
        assertRejectedBy(
                "uk_seasons_previous_season",
                () -> insertSeason(teamId, "중복 후속 시즌", summerId)
        );
    }

    @DisplayName("자동 회차는 시즌의 같은 예정일에 하나만 만들고 루틴 마감 규칙은 필요한 값을 함께 저장한다")
    @Test
    void roundAutomationRequiresUniqueOccurrenceAndCompleteDeadlineRule() {
        String teamId = insertTeam();
        String seasonId = insertSeason(teamId, "자동화 시즌", null);
        String roleId = insertRole(teamId, seasonId);
        String routineId = insertRoutine(seasonId, roleId);
        String roundId = insertAutomaticRound(seasonId, "자동 1회차");

        assertRejectedBy(
                "uk_season_rounds_season_occurrence",
                () -> insertAutomaticRound(seasonId, "자동 2회차")
        );

        assertRejectedBy("chk_routines_deadline_rule_complete", () -> jdbc.update(
                "UPDATE routines SET deadline_day_offset = -1 WHERE id = UUID_TO_BIN(?)",
                routineId
        ));
        assertRejectedBy("chk_routines_deadline_rule_complete", () -> jdbc.update(
                "UPDATE routines SET deadline_time = '10:00:00' WHERE id = UUID_TO_BIN(?)",
                routineId
        ));
        jdbc.update(
                "UPDATE routines SET deadline_day_offset = -1, deadline_time = '10:00:00' "
                        + "WHERE id = UUID_TO_BIN(?)",
                routineId
        );

        String executionId = insertExecution(roundId, routineId, roleId);
        assertRejectedBy("chk_routine_executions_deadline_rule_complete", () -> jdbc.update(
                "UPDATE routine_executions SET deadline_day_offset = -1, deadline_time = '10:00:00' "
                        + "WHERE id = UUID_TO_BIN(?)",
                executionId
        ));
        jdbc.update(
                "UPDATE routine_executions "
                        + "SET deadline_day_offset = -1, deadline_time = '10:00:00', deadline_at = ? "
                        + "WHERE id = UUID_TO_BIN(?)",
                AT,
                executionId
        );
    }

    @DisplayName("역할마다 진행 중인 바통은 하나이고 바통 취소는 넘기는 구성원으로만 기록한다")
    @Test
    void roleHandoffAllowsSingleOpenHandoffCancelledByOutgoingMember() {
        String teamId = insertTeam();
        String seasonId = insertSeason(teamId, "바통 시즌", null);
        String fromMemberId = insertMember(teamId, "이전 담당자");
        String toMemberId = insertMember(teamId, "다음 담당자");
        String roleId = insertRole(teamId, seasonId);
        String handoffId = insertPreparingHandoff(teamId, seasonId, roleId, fromMemberId, toMemberId);

        assertRejectedBy(
                "uk_role_handoffs_active_role",
                () -> insertPreparingHandoff(teamId, seasonId, roleId, fromMemberId, toMemberId)
        );
        assertRejectedBy("chk_role_handoffs_lifecycle", () -> cancelHandoff(handoffId, toMemberId));

        cancelHandoff(handoffId, fromMemberId);
        insertPreparingHandoff(teamId, seasonId, roleId, fromMemberId, toMemberId);
    }

    @DisplayName("로그인 신원은 공급자 subject와 계정별 공급자가 유일하고 자체 이메일 인증 요청은 신원마다 하나다")
    @Test
    void accountIdentityKeepsProviderUniquenessAndVerificationChallengeRules() {
        String accountId = insertAccount();
        String otherAccountId = insertAccount();
        String googleSubject = "google-" + accountId;
        String googleIdentityId = insertIdentity(accountId, "GOOGLE", googleSubject, "same@example.com", true);
        insertIdentity(otherAccountId, "NAVER", "naver-" + otherAccountId, "same@example.com", true);

        assertRejectedBy(
                "uk_account_identities_provider_subject",
                () -> insertIdentity(otherAccountId, "GOOGLE", googleSubject, "other@example.com", true)
        );
        assertRejectedBy(
                "uk_account_identities_account_provider",
                () -> insertIdentity(accountId, "GOOGLE", "google-other-" + accountId, "other@example.com", true)
        );

        String localEmail = "local-" + accountId + "@example.com";
        String localIdentityId = insertIdentity(accountId, "LOCAL_EMAIL", localEmail, localEmail, false);
        String upperEmail = "UPPER-" + otherAccountId + "@example.com";
        assertRejectedBy(
                "chk_account_identities_local_email",
                () -> insertIdentity(otherAccountId, "LOCAL_EMAIL", upperEmail, upperEmail, false)
        );

        insertChallenge(localIdentityId, randomHash());
        assertRejectedBy(
                "uk_email_verification_challenges_identity",
                () -> insertChallenge(localIdentityId, randomHash())
        );
        assertRejectedBy(
                "chk_email_verification_challenges_token_hash",
                () -> insertChallenge(googleIdentityId, "not-a-sha256-hash")
        );
    }

    @DisplayName("이메일 인증 전달 아웃박스는 형식이 맞는 암호문만 저장하고 임대 없이 처리 중 상태가 될 수 없다")
    @Test
    void emailVerificationOutboxRequiresOpaquePayloadAndLease() {
        String accountId = insertAccount();
        String email = "outbox-" + accountId + "@example.com";
        String identityId = insertIdentity(accountId, "LOCAL_EMAIL", email, email, false);
        insertEmailOutbox(identityId, "encryptedPayloadValue000000000000000000000000000000");

        assertRejectedBy("chk_email_verification_outbox_payload", () -> insertEmailOutbox(identityId, "***"));
        assertRejectedBy("chk_email_verification_outbox_lease", () -> jdbc.update(
                "UPDATE email_verification_delivery_outbox SET delivery_status = 'PROCESSING' "
                        + "WHERE identity_id = UUID_TO_BIN(?)",
                identityId
        ));
    }

    @DisplayName("계정은 팀마다 구성원 하나만 연결하고 구성원은 한 계정과 자기 팀으로만 연결된다")
    @Test
    void accountTeamMembershipBindsOneMemberOfSameTeam() {
        String accountId = insertAccount();
        String otherAccountId = insertAccount();
        String teamId = insertTeam();
        String otherTeamId = insertTeam();
        String memberId = insertMember(teamId, "첫 구성원");
        String otherMemberId = insertMember(teamId, "다른 구성원");
        insertMembership(accountId, teamId, memberId);

        assertRejectedBy(
                "uk_account_team_memberships_account_team",
                () -> insertMembership(accountId, teamId, otherMemberId)
        );
        assertRejectedBy(
                "uk_account_team_memberships_member",
                () -> insertMembership(otherAccountId, teamId, memberId)
        );
        assertRejectedBy(
                "fk_account_team_memberships_member_team",
                () -> insertMembership(otherAccountId, otherTeamId, otherMemberId)
        );
    }

    @DisplayName("ROUND 방 매핑은 영구 tombstone과 같은 범위만 쓰고 끝난 방 ID는 다시 발급하지 않는다")
    @Test
    void roundRoomMappingFollowsPermanentTombstone() {
        String teamId = insertTeam();
        String seasonId = insertSeason(teamId, "ROUND 시즌", null);
        String roleId = insertRole(teamId, seasonId);
        String resourceId = insertResource(roleId);
        String otherResourceId = insertResource(roleId);
        String unmappedResourceId = insertResource(roleId);
        insertTombstone("abcd-efgh-jkmn", teamId, seasonId, resourceId);
        insertMapping("abcd-efgh-jkmn", teamId, seasonId, resourceId);
        insertTombstone("npqr-stuv-wxyz", teamId, seasonId, otherResourceId);
        insertTombstone("2345-6789-abcd", teamId, seasonId, resourceId);

        assertRejectedBy(
                "fk_round_room_mappings_tombstone_snapshot",
                () -> insertMapping("npqr-stuv-wxyz", teamId, seasonId, unmappedResourceId)
        );
        assertRejectedBy(
                "uk_round_room_mappings_resource",
                () -> insertMapping("2345-6789-abcd", teamId, seasonId, resourceId)
        );
        assertRejectedBy(
                "chk_round_room_tombstones_room_id",
                () -> insertTombstone("not-a-room", teamId, seasonId, otherResourceId)
        );

        jdbc.update("DELETE FROM round_room_mappings WHERE room_id = 'abcd-efgh-jkmn'");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM round_room_tombstones WHERE room_id = 'abcd-efgh-jkmn'",
                Integer.class
        )).isOne();
        assertRejectedBy(
                "round_room_tombstones.PRIMARY",
                () -> insertTombstone("abcd-efgh-jkmn", teamId, seasonId, resourceId)
        );
        assertRejectedBy("chk_round_room_tombstones_timeline", () -> jdbc.update(
                "UPDATE round_room_tombstones SET ended_at = ? WHERE room_id = 'abcd-efgh-jkmn'",
                AT.minusMinutes(1)
        ));
    }

    @DisplayName("WATCH 감시 아웃박스는 이벤트 ID가 유일하고 감시 중지 이벤트에는 대상 URL이 없다")
    @Test
    void watchMonitorOutboxKeepsEventIdentityAndTargetRule() {
        String eventId = newId();
        insertWatchOutbox(eventId, "ACTIVE", "https://example.com/operations", null);
        insertWatchOutbox(newId(), "INACTIVE", null, null);

        assertRejectedBy(
                "uk_watch_monitor_outbox_event",
                () -> insertWatchOutbox(eventId, "ACTIVE", "https://example.org", null)
        );
        assertRejectedBy(
                "chk_watch_monitor_outbox_target",
                () -> insertWatchOutbox(newId(), "INACTIVE", "https://example.net", null)
        );
    }

    @DisplayName("WATCH 보상 이벤트는 존재하는 원본마다 하나씩 감시 중지로만 기록한다")
    @Test
    void watchCompensationTargetsExistingSourceOnceAsInactive() {
        long sourceId = insertWatchOutbox(newId(), "ACTIVE", "https://example.com/rejected", null);
        long otherSourceId = insertWatchOutbox(newId(), "ACTIVE", "https://example.org/rejected", null);
        insertWatchOutbox(newId(), "INACTIVE", null, sourceId);

        assertRejectedBy(
                "uk_watch_monitor_outbox_compensation_source",
                () -> insertWatchOutbox(newId(), "INACTIVE", null, sourceId)
        );
        assertRejectedBy(
                "fk_watch_monitor_outbox_compensation_source",
                () -> insertWatchOutbox(newId(), "INACTIVE", null, Long.MAX_VALUE)
        );
        assertRejectedBy(
                "chk_watch_monitor_outbox_compensation",
                () -> insertWatchOutbox(newId(), "ACTIVE", "https://example.org/compensation", otherSourceId)
        );
    }

    @DisplayName("WATCH 상태 인박스는 변경 시각의 나노초 나머지를 0~999로 제한한다")
    @Test
    void watchHealthInboxLimitsNanoRemainder() {
        String resourceId = newId();
        insertWatchInbox(resourceId, 999);

        assertRejectedBy(
                "chk_watch_health_event_inbox_nano_remainder",
                () -> insertWatchInbox(resourceId, 1_000)
        );
    }

    @DisplayName("BRIEF와 CAL 아웃박스는 임대 없이 처리 중 상태로 바꿀 수 없다")
    @Test
    void briefAndCalendarOutboxRequireLeaseWhileProcessing() {
        String briefEventId = newId();
        String signalId = newId();
        jdbc.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id, signal_id, workspace_id, season_id, event_type, event_version,
                    source_severity, source_reference, aggregate_revision, occurred_at,
                    event_state, available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    'ROLE_UNASSIGNED', 2, 'CRITICAL', ?, 1, ?, 'ACTIVE', ?
                )
                """,
                briefEventId,
                signalId,
                newId(),
                newId(),
                "baton-continuity:" + signalId,
                AT,
                AT
        );
        String calendarEventId = newId();
        jdbc.update(
                """
                INSERT INTO calendar_snapshot_outbox (
                    event_id, source_item_id, season_id, occurred_at, calendar_status, summary,
                    time_type, at_instant, source_updated_at, available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'ACTIVE', '모임',
                    'UTC_POINT', ?, ?, ?
                )
                """,
                calendarEventId,
                newId(),
                newId(),
                AT,
                AT.plusDays(1),
                AT,
                AT
        );

        assertRejectedBy("chk_brief_continuity_outbox_lease", () -> jdbc.update(
                "UPDATE brief_continuity_outbox SET delivery_status = 'PROCESSING' "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                briefEventId
        ));
        assertRejectedBy("chk_calendar_snapshot_outbox_delivery_lifecycle", () -> jdbc.update(
                "UPDATE calendar_snapshot_outbox SET delivery_status = 'PROCESSING' "
                        + "WHERE event_id = UUID_TO_BIN(?)",
                calendarEventId
        ));
    }

    // 메시지에 제약 이름이 있어야 다른 제약이나 누락된 필수 열 때문에 실패한 경우와 구분된다.
    private static void assertRejectedBy(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String randomHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static String insertTeam() {
        String teamId = newId();
        jdbc.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), '제약 검증 팀', ?)",
                teamId,
                randomHash()
        );
        return teamId;
    }

    private static String insertSeason(String teamId, String name, String previousSeasonId) {
        String seasonId = newId();
        jdbc.update(
                """
                INSERT INTO seasons (
                    id, team_id, name, start_date, end_date, previous_season_id, time_zone
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), 'Asia/Seoul')
                """,
                seasonId,
                teamId,
                name,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                previousSeasonId
        );
        return seasonId;
    }

    private static void endSeason(String seasonId) {
        jdbc.update("UPDATE seasons SET ended_at = ? WHERE id = UUID_TO_BIN(?)", AT, seasonId);
    }

    private static String insertMember(String teamId, String name) {
        String memberId = newId();
        jdbc.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                memberId,
                teamId,
                name
        );
        return memberId;
    }

    private static String insertRole(String teamId, String seasonId) {
        String roleId = newId();
        jdbc.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), '진행자', '모임을 진행합니다')",
                roleId,
                teamId,
                seasonId
        );
        return roleId;
    }

    private static String insertRoutine(String seasonId, String ownerRoleId) {
        String routineId = newId();
        jdbc.update(
                "INSERT INTO routines (id, season_id, title, phase, due_label, owner_role_id, detail) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), '질문 모으기', 'BEFORE', '모임 전', "
                        + "UUID_TO_BIN(?), '질문을 한곳에 모읍니다')",
                routineId,
                seasonId,
                ownerRoleId
        );
        return routineId;
    }

    private static String insertAutomaticRound(String seasonId, String name) {
        String roundId = newId();
        jdbc.update(
                "INSERT INTO season_rounds ("
                        + "id, season_id, name, meeting_date, origin, scheduled_occurrence_date, scheduled_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, 'AUTOMATIC', ?, ?)",
                roundId,
                seasonId,
                name,
                OCCURRENCE_DATE,
                OCCURRENCE_DATE,
                OCCURRENCE_DATE.atTime(10, 30)
        );
        return roundId;
    }

    private static String insertExecution(String roundId, String routineId, String ownerRoleId) {
        String executionId = newId();
        jdbc.update(
                "INSERT INTO routine_executions ("
                        + "id, season_round_id, routine_id, title, phase, due_label, owner_role_id, status, detail"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), '질문 모으기', 'BEFORE', "
                        + "'모임 전', UUID_TO_BIN(?), 'WAITING', '질문을 한곳에 모읍니다')",
                executionId,
                roundId,
                routineId,
                ownerRoleId
        );
        return executionId;
    }

    private static void insertIdempotency(String teamId, String seasonId, String operation) {
        jdbc.update(
                "INSERT INTO content_creation_idempotency ("
                        + "id, team_id, season_id, operation, idempotency_hash, request_fingerprint, resource_id"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?))",
                newId(),
                teamId,
                seasonId,
                operation,
                randomHash(),
                randomHash(),
                newId()
        );
    }

    private static String insertPreparingHandoff(
            String teamId,
            String seasonId,
            String roleId,
            String fromMemberId,
            String toMemberId
    ) {
        String handoffId = newId();
        jdbc.update(
                "INSERT INTO role_handoffs ("
                        + "id, team_id, season_id, role_id, from_member_id, to_member_id, "
                        + "incoming_assignment_start_date, status, prepared_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), "
                        + "UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'PREPARING', ?)",
                handoffId,
                teamId,
                seasonId,
                roleId,
                fromMemberId,
                toMemberId,
                LocalDate.of(2026, 8, 1),
                AT
        );
        return handoffId;
    }

    private static void cancelHandoff(String handoffId, String cancelledByMemberId) {
        jdbc.update(
                "UPDATE role_handoffs SET status = 'CANCELLED', cancelled_at = ?, "
                        + "cancelled_by_member_id = UUID_TO_BIN(?) WHERE id = UUID_TO_BIN(?)",
                AT.plusMinutes(10),
                cancelledByMemberId,
                handoffId
        );
    }

    private static String insertAccount() {
        String accountId = newId();
        jdbc.update(
                "INSERT INTO accounts (id, display_name, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), '제약 검증 계정', ?, ?)",
                accountId,
                AT,
                AT
        );
        return accountId;
    }

    private static String insertIdentity(
            String accountId,
            String provider,
            String providerSubject,
            String email,
            boolean emailVerified
    ) {
        String identityId = newId();
        jdbc.update(
                "INSERT INTO account_identities ("
                        + "id, account_id, provider, provider_subject, email_snapshot, email_verified, created_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?)",
                identityId,
                accountId,
                provider,
                providerSubject,
                email,
                emailVerified,
                AT
        );
        return identityId;
    }

    private static void insertChallenge(String identityId, String tokenHash) {
        jdbc.update(
                "INSERT INTO email_verification_challenges ("
                        + "id, identity_id, token_hash, expires_at, created_at, purpose"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, 'REGISTRATION')",
                newId(),
                identityId,
                tokenHash,
                AT.plusMinutes(30),
                AT
        );
    }

    private static void insertEmailOutbox(String identityId, String payloadCiphertext) {
        jdbc.update(
                "INSERT INTO email_verification_delivery_outbox ("
                        + "identity_id, payload_ciphertext, payload_nonce, challenge_token_hash, expires_at, "
                        + "delivery_status, available_at, created_at"
                        + ") VALUES (UUID_TO_BIN(?), ?, 'nonceValue000000', ?, ?, 'PENDING', ?, ?)",
                identityId,
                payloadCiphertext,
                randomHash(),
                AT.plusMinutes(30),
                AT,
                AT
        );
    }

    private static void insertMembership(String accountId, String teamId, String memberId) {
        jdbc.update(
                "INSERT INTO account_team_memberships (id, account_id, team_id, member_id, claimed_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                newId(),
                accountId,
                teamId,
                memberId,
                AT
        );
    }

    private static String insertResource(String roleId) {
        String resourceId = newId();
        jdbc.update(
                "INSERT INTO role_resources (id, role_id, title, url, created_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'ROUND 자료', ?, ?)",
                resourceId,
                roleId,
                "https://example.com/" + resourceId,
                AT
        );
        return resourceId;
    }

    private static void insertTombstone(String roomId, String teamId, String seasonId, String resourceId) {
        jdbc.update(
                "INSERT INTO round_room_tombstones (room_id, team_id, season_id, resource_id, created_at) "
                        + "VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                roomId,
                teamId,
                seasonId,
                resourceId,
                AT
        );
    }

    private static void insertMapping(String roomId, String teamId, String seasonId, String resourceId) {
        jdbc.update(
                "INSERT INTO round_room_mappings (id, room_id, team_id, season_id, resource_id, created_at) "
                        + "VALUES (UUID_TO_BIN(?), ?, UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                newId(),
                roomId,
                teamId,
                seasonId,
                resourceId,
                AT
        );
    }

    private static long insertWatchOutbox(
            String eventId,
            String monitoringState,
            String targetUrl,
            Long compensationForId
    ) {
        String resourceId = newId();
        jdbc.update(
                "INSERT INTO watch_monitor_outbox ("
                        + "event_id, resource_id, resource_reference, monitoring_state, target_url, "
                        + "compensation_for_id, occurred_at, available_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)",
                eventId,
                resourceId,
                "baton-manager:pilot:role-resource:" + resourceId,
                monitoringState,
                targetUrl,
                compensationForId,
                AT,
                AT
        );
        return jdbc.queryForObject(
                "SELECT id FROM watch_monitor_outbox WHERE event_id = UUID_TO_BIN(?)",
                Long.class,
                eventId
        );
    }

    private static void insertWatchInbox(String resourceId, int nanoRemainder) {
        jdbc.update(
                "INSERT INTO watch_health_event_inbox ("
                        + "event_id, resource_id, event_type, resource_reference, source_revision, "
                        + "previous_health, current_health, changed_at, changed_at_nano_remainder, "
                        + "payload_fingerprint, accepted_at"
                        + ") VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'RESOURCE_HEALTH_CHANGED', ?, 17, "
                        + "'DEGRADED', 'BROKEN', ?, ?, ?, ?)",
                newId(),
                resourceId,
                "baton-manager:pilot:role-resource:" + resourceId,
                AT,
                nanoRemainder,
                new byte[32],
                AT.plusMinutes(1)
        );
    }
}
