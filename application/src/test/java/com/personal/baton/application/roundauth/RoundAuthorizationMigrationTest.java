package com.personal.baton.application.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
class RoundAuthorizationMigrationTest {

    private static final String ACCOUNT_ID = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_ACCOUNT_ID = "10000000-0000-0000-0000-000000000002";
    private static final String TEAM_ID = "20000000-0000-0000-0000-000000000001";
    private static final String OTHER_TEAM_ID = "20000000-0000-0000-0000-000000000002";
    private static final String SEASON_ID = "30000000-0000-0000-0000-000000000001";
    private static final String MEMBER_ID = "40000000-0000-0000-0000-000000000001";
    private static final String OTHER_MEMBER_ID = "40000000-0000-0000-0000-000000000002";
    private static final String ROLE_ID = "50000000-0000-0000-0000-000000000001";
    private static final String RESOURCE_ID = "60000000-0000-0000-0000-000000000001";
    private static final String OTHER_RESOURCE_ID = "60000000-0000-0000-0000-000000000002";
    private static final String ROOM_ID = "abcd-efgh-jkmn";
    private static final String OTHER_ROOM_ID = "npqr-stuv-wxyz";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_round_authorization_migration")
            .withUsername("baton")
            .withPassword("password");

    @Test
    @DisplayName("V20은 기존 계정과 workspace를 보존하며 멤버십, 영구 tombstone과 활성 방 유일성을 추가한다")
    void addRoundAuthorizationConstraintsWithoutReusingRoomIds() {
        migrateTo("19");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        seedV19AccountAndWorkspace(jdbcTemplate);

        migrateTo("20");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM accounts WHERE id = UUID_TO_BIN(?)",
                String.class,
                ACCOUNT_ID
        )).isEqualTo("파일럿 계정");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT collation_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'round_room_tombstones' AND column_name = 'room_id'",
                String.class
        )).isEqualTo("ascii_bin");

        insertMembership(jdbcTemplate, ACCOUNT_ID, TEAM_ID, MEMBER_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_team_memberships",
                Integer.class
        )).isOne();

        assertThatThrownBy(() -> insertMembership(
                jdbcTemplate,
                ACCOUNT_ID,
                TEAM_ID,
                OTHER_MEMBER_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMembership(
                jdbcTemplate,
                OTHER_ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMembership(
                jdbcTemplate,
                OTHER_ACCOUNT_ID,
                OTHER_TEAM_ID,
                OTHER_MEMBER_ID
        )).isInstanceOf(DataAccessException.class);

        insertTombstone(jdbcTemplate, ROOM_ID, TEAM_ID, SEASON_ID, RESOURCE_ID);
        insertMapping(jdbcTemplate, ROOM_ID, TEAM_ID, SEASON_ID, RESOURCE_ID);
        insertTombstone(
                jdbcTemplate,
                OTHER_ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                OTHER_RESOURCE_ID
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(team_id) FROM round_room_tombstones WHERE room_id = ?",
                String.class,
                ROOM_ID
        )).isEqualTo(TEAM_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(season_id) FROM round_room_tombstones WHERE room_id = ?",
                String.class,
                ROOM_ID
        )).isEqualTo(SEASON_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(resource_id) FROM round_room_tombstones WHERE room_id = ?",
                String.class,
                ROOM_ID
        )).isEqualTo(RESOURCE_ID);

        assertThatThrownBy(() -> insertMapping(
                jdbcTemplate,
                OTHER_ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMapping(
                jdbcTemplate,
                OTHER_ROOM_ID,
                OTHER_TEAM_ID,
                SEASON_ID,
                OTHER_RESOURCE_ID
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertTombstone(
                jdbcTemplate,
                "not-a-room",
                TEAM_ID,
                SEASON_ID,
                OTHER_RESOURCE_ID
        ))
                .isInstanceOf(DataAccessException.class);

        jdbcTemplate.update(
                "DELETE FROM round_room_mappings WHERE room_id = ?",
                ROOM_ID
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM round_room_tombstones WHERE room_id = ?",
                Integer.class,
                ROOM_ID
        )).isOne();
        assertThatThrownBy(() -> insertTombstone(
                jdbcTemplate,
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID
        ))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE round_room_tombstones SET ended_at = ? WHERE room_id = ?",
                LocalDateTime.of(2026, 8, 7, 23, 59),
                ROOM_ID
        )).isInstanceOf(DataAccessException.class);

    }

    private void migrateTo(String target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
    }

    private void seedV19AccountAndWorkspace(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO accounts (id, display_name, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?)",
                ACCOUNT_ID,
                "파일럿 계정",
                LocalDateTime.of(2026, 8, 8, 9, 0),
                LocalDateTime.of(2026, 8, 8, 9, 0)
        );
        jdbcTemplate.update(
                "INSERT INTO accounts (id, display_name, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?)",
                OTHER_ACCOUNT_ID,
                "다른 계정",
                LocalDateTime.of(2026, 8, 8, 9, 0),
                LocalDateTime.of(2026, 8, 8, 9, 0)
        );
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                TEAM_ID,
                "ROUND 파일럿 팀",
                "a".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO teams (id, name, access_key_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                OTHER_TEAM_ID,
                "다른 팀",
                "b".repeat(64)
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                SEASON_ID,
                TEAM_ID,
                "2026 파일럿",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31)
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                MEMBER_ID,
                TEAM_ID,
                "첫 구성원"
        );
        jdbcTemplate.update(
                "INSERT INTO members (id, team_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                OTHER_MEMBER_ID,
                TEAM_ID,
                "다른 구성원"
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, team_id, season_id, name, purpose) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "스터디원",
                "ROUND 자료를 관리합니다"
        );
        insertResource(jdbcTemplate, RESOURCE_ID, "첫 자료");
        insertResource(jdbcTemplate, OTHER_RESOURCE_ID, "다른 자료");
    }

    private void insertResource(JdbcTemplate jdbcTemplate, String resourceId, String title) {
        jdbcTemplate.update(
                "INSERT INTO role_resources (id, role_id, title, url, description, created_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                resourceId,
                ROLE_ID,
                title,
                "https://example.com/" + resourceId,
                "ROUND 자료",
                LocalDateTime.of(2026, 8, 8, 9, 0)
        );
    }

    private void insertMembership(
            JdbcTemplate jdbcTemplate,
            String accountId,
            String teamId,
            String memberId
    ) {
        jdbcTemplate.update(
                "INSERT INTO account_team_memberships "
                        + "(id, account_id, team_id, member_id, claimed_at) "
                        + "VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                accountId,
                teamId,
                memberId,
                LocalDateTime.of(2026, 8, 8, 10, 0)
        );
    }

    private void insertTombstone(
            JdbcTemplate jdbcTemplate,
            String roomId,
            String teamId,
            String seasonId,
            String resourceId
    ) {
        jdbcTemplate.update(
                "INSERT INTO round_room_tombstones "
                        + "(room_id, team_id, season_id, resource_id, created_at) "
                        + "VALUES (?, UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                roomId,
                teamId,
                seasonId,
                resourceId,
                LocalDateTime.of(2026, 8, 8, 10, 0)
        );
    }

    private void insertMapping(
            JdbcTemplate jdbcTemplate,
            String roomId,
            String teamId,
            String seasonId,
            String resourceId
    ) {
        jdbcTemplate.update(
                "INSERT INTO round_room_mappings "
                        + "(id, room_id, team_id, season_id, resource_id, created_at) "
                        + "VALUES (UUID_TO_BIN(UUID()), ?, UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                roomId,
                teamId,
                seasonId,
                resourceId,
                LocalDateTime.of(2026, 8, 8, 10, 0)
        );
    }
}
