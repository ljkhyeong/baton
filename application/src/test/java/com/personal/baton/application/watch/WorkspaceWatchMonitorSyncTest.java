package com.personal.baton.application.watch;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateRoleResourceCommand;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "baton.round-automation.poll-interval=PT24H",
                "baton.watch.enabled=true",
                "baton.watch.base-url=https://watch.invalid",
                "baton.watch.bearer-token=watch-test-token-00000000000000000001",
                "baton.watch.source-namespace=study-pilot",
                "baton.watch.dispatch-interval=PT24H",
                "baton.watch.reconciliation-initial-delay=PT24H",
                "baton.watch.reconciliation-interval=PT24H"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceWatchMonitorSyncTest {

    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";
    private static final String INITIAL_URL = "https://docs.example.com/study-guide";
    private static final String RESTORED_URL = "https://docs.example.com/study-guide-v2";

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_workspace_watch_sync")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WorkspaceLifecycleUseCase lifecycleUseCase;

    @Autowired
    private WorkspacePeopleUseCase peopleUseCase;

    @Autowired
    private WorkspaceRecordsUseCase recordsUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("역할 자료와 시즌 변경은 WATCH outbox에 중복 없이 단조 증가하는 snapshot을 저장한다")
    @Test
    void recordsWorkspaceResourceAndSeasonChangesInWatchOutbox() {
        CreatedWorkspaceResult workspace = lifecycleUseCase.createWorkspace(
                "workspace-watch-sync-create-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "WATCH 연동 스터디",
                        "첫 시즌",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of("김준호")
                )
        );
        RoleResult role = peopleUseCase.createRole(
                workspace.teamId(),
                workspace.seasonId(),
                "workspace-watch-sync-role-000001",
                workspace.accessKey(),
                new CreateRoleCommand(
                        "자료 담당",
                        "스터디 자료를 최신 상태로 유지합니다",
                        null,
                        null,
                        null,
                        null,
                        List.of("공유 문서 정리"),
                        null
                )
        );
        CreateRoleResourceCommand createCommand = new CreateRoleResourceCommand(
                role.id(),
                "스터디 가이드",
                INITIAL_URL,
                "첫 모임 전에 읽을 자료"
        );
        String resourceIdempotencyKey = "workspace-watch-sync-resource-0001";

        RoleResourceResult resource = recordsUseCase.createRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                resourceIdempotencyKey,
                workspace.accessKey(),
                createCommand
        );

        assertThat(storedSnapshots(resource.id()))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.monitoringState()).isEqualTo("ACTIVE");
                    assertThat(snapshot.targetUrl()).isEqualTo(INITIAL_URL);
                });

        assertThat(recordsUseCase.createRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                resourceIdempotencyKey,
                workspace.accessKey(),
                createCommand
        )).isEqualTo(resource);
        recordsUseCase.updateRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                resource.id(),
                workspace.accessKey(),
                new UpdateRoleResourceCommand(
                        role.id(),
                        "스터디 가이드 개정",
                        INITIAL_URL,
                        "설명만 고쳐도 WATCH snapshot은 중복 저장하지 않습니다"
                )
        );

        assertThat(storedSnapshots(resource.id())).hasSize(1);

        recordsUseCase.updateRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                resource.id(),
                workspace.accessKey(),
                new UpdateRoleResourceCommand(
                        role.id(),
                        "인증 링크",
                        INITIAL_URL + "?token=secret",
                        "query가 있는 URL은 WATCH에 복사하지 않습니다"
                )
        );
        recordsUseCase.updateRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                resource.id(),
                workspace.accessKey(),
                new UpdateRoleResourceCommand(
                        role.id(),
                        "스터디 가이드 v2",
                        RESTORED_URL,
                        "공개 URL로 다시 감시합니다"
                )
        );
        lifecycleUseCase.updateSeasonEnding(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey(),
                true
        );
        lifecycleUseCase.updateSeasonEnding(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey(),
                false
        );

        List<StoredSnapshot> snapshots = storedSnapshots(resource.id());
        assertThat(snapshots)
                .extracting(StoredSnapshot::monitoringState)
                .containsExactly("ACTIVE", "INACTIVE", "ACTIVE", "INACTIVE", "ACTIVE");
        assertThat(snapshots)
                .extracting(StoredSnapshot::targetUrl)
                .containsExactly(INITIAL_URL, null, RESTORED_URL, null, RESTORED_URL);
        assertThat(snapshots)
                .extracting(StoredSnapshot::resourceReference)
                .containsOnly("baton-manager:study-pilot:role-resource:" + resource.id());
        assertThat(snapshots)
                .extracting(StoredSnapshot::sourceRevision)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    private List<StoredSnapshot> storedSnapshots(UUID resourceId) {
        return jdbcTemplate.query(
                """
                SELECT id, resource_reference, monitoring_state, target_url
                FROM watch_monitor_outbox
                WHERE resource_id = UUID_TO_BIN(?)
                ORDER BY id
                """,
                (resultSet, rowNumber) -> new StoredSnapshot(
                        resultSet.getLong("id"),
                        resultSet.getString("resource_reference"),
                        resultSet.getString("monitoring_state"),
                        resultSet.getString("target_url")
                ),
                resourceId.toString()
        );
    }

    private record StoredSnapshot(
            long sourceRevision,
            String resourceReference,
            String monitoringState,
            String targetUrl
    ) {
    }
}
