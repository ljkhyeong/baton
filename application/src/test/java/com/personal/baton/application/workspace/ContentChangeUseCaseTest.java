package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateRoleResourceCommand;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.workspace.ContentRecordKind;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateDecisionCommand;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Optional;
import static org.mockito.Mockito.when;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BatonApplication.class, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.identity.email-verification.outbox-encryption-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "baton.round-automation.poll-interval=PT24H",
        "baton.identity.email-verification.dispatch-interval=PT24H"
})
class ContentChangeUseCaseTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("content_change_test").withUsername("baton").withPassword("password");
    @Autowired WorkspaceLifecycleUseCase lifecycle;
    @Autowired WorkspacePeopleUseCase people;
    @Autowired WorkspaceRecordsUseCase records;
    @Autowired IdentityRepository identities;
    @Autowired Clock clock;

    @Autowired ContentChangeUseCase changes;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentAccountProvider current;

    @Test
    @DisplayName("수정 이력은 원본과 함께 저장하고 변경 전후·계정·공유 키 사용·보관·롤백을 구분한다")
    void recordsCommittedChangesOnly() {
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("수정 이력 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("민서")));
        var team = workspace.teamId(); var season = workspace.seasonId(); var key = workspace.accessKey();
        var member = lifecycle.getWorkspace(team, season, key).members().getFirst();
        var role = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("기록 담당", "기억을 남깁니다", null, null, null, null, List.of(), null));
        var decision = records.createDecision(team, season, UUID.randomUUID().toString(), key,
                new CreateDecisionCommand("회의", "기존 이유", null, member.id(), List.of(role.id())));
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes()).isEmpty();
        var actor = identities.saveAccount(Account.create(UUID.randomUUID(), "민서 계정", clock.instant()));
        when(current.currentAccountId()).thenReturn(Optional.of(actor.getId()));
        var command = new UpdateDecisionCommand("회의", "수정 이유", null, member.id(), List.of(role.id()));
        records.updateDecision(team, season, decision.id(), key, command);
        var change = changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes().getFirst();
        assertThat(change.actorAccountId()).isEqualTo(actor.getId());
        assertThat(change.actorName()).isEqualTo("민서 계정");
        assertThat(change.fields()).singleElement().satisfies(field -> {
            assertThat(field.fieldName()).isEqualTo("이유"); assertThat(field.beforeValue()).isEqualTo("기존 이유");
            assertThat(field.afterValue()).isEqualTo("수정 이유");
        });
        records.updateDecision(team, season, decision.id(), key, command);
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes()).hasSize(1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            records.updateDecisionArchive(team, season, decision.id(), key, true); status.setRollbackOnly();
        });
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes()).hasSize(1);
        when(current.currentAccountId()).thenReturn(Optional.empty());
        var resource = records.createRoleResource(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleResourceCommand(role.id(), "운영안", "https://example.com/a", null));
        records.updateRoleResource(team, season, resource.id(), key,
                new UpdateRoleResourceCommand(role.id(), "운영안", "https://example.com/b", "내용 확인"));
        var resourceChange = changes.getHistory(team, season, ContentRecordKind.ROLE_RESOURCE, resource.id(), key).changes().getFirst();
        assertThat(resourceChange.actorAccountId()).isNull(); assertThat(resourceChange.actorName()).isEqualTo("공유 키 사용자");
        assertThat(resourceChange.fields()).extracting(ContentChangeUseCase.FieldChangeResult::fieldName).containsExactly("주소", "설명");
        records.updateRoleResourceArchive(team, season, resource.id(), key, true);
        records.updateRoleResourceArchive(team, season, resource.id(), key, false);
        assertThat(changes.getHistory(team, season, ContentRecordKind.ROLE_RESOURCE, resource.id(), key).changes()).hasSize(3);
        assertThatThrownBy(() -> changes.getHistory(team, season, ContentRecordKind.DECISION, resource.id(), key))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), "wrong"))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        lifecycle.updateSeasonEnding(team, season, key, true);
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes()).hasSize(1);
    }

    @Test
    @DisplayName("결정 이력은 작성자와 관련 역할의 변경을 기록하고 역할 순서 변경은 제외한다")
    void recordsChangedAuthorAndSelectedRoles() {
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("관련 역할 이력 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("민서")));
        var team = workspace.teamId(); var season = workspace.seasonId(); var key = workspace.accessKey();
        var author = lifecycle.getWorkspace(team, season, key).members().getFirst();
        var nextAuthor = people.createMember(team, season, UUID.randomUUID().toString(), key, new CreateMemberCommand("준호"));
        var recordRole = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("기록 담당", "회의를 기록합니다", null, null, null, null, List.of("회의록 작성"), null));
        var operationsRole = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("운영 담당", "일정을 관리합니다", null, null, null, null, List.of(), null));
        people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("발표 담당", "발표를 준비합니다", null, null, null, null, List.of(), null));
        var decision = records.createDecision(team, season, UUID.randomUUID().toString(), key,
                new CreateDecisionCommand("회의", "운영 방식", null, author.id(), List.of(recordRole.id())));

        records.updateDecision(team, season, decision.id(), key,
                new UpdateDecisionCommand("회의", "운영 방식", null, nextAuthor.id(), List.of(operationsRole.id(), recordRole.id())));
        var change = changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes().getFirst();
        assertThat(change.fields()).extracting(ContentChangeUseCase.FieldChangeResult::fieldName,
                        ContentChangeUseCase.FieldChangeResult::beforeValue, ContentChangeUseCase.FieldChangeResult::afterValue)
                .containsExactly(tuple("작성자", "민서", "준호"), tuple("관련 역할", "기록 담당", "기록 담당, 운영 담당"));

        records.updateDecision(team, season, decision.id(), key,
                new UpdateDecisionCommand("회의", "운영 방식", null, nextAuthor.id(), List.of(recordRole.id(), operationsRole.id())));
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes()).hasSize(1);
        records.updateDecision(team, season, decision.id(), key,
                new UpdateDecisionCommand("회의", "운영 방식", null, nextAuthor.id(), List.of(operationsRole.id())));
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes().getFirst().fields())
                .containsExactly(new ContentChangeUseCase.FieldChangeResult("관련 역할", "기록 담당, 운영 담당", "운영 담당"));

        records.updateDecisionArchive(team, season, decision.id(), key, true);
        assertThat(changes.getHistory(team, season, ContentRecordKind.DECISION, decision.id(), key).changes().getFirst().fields())
                .containsExactly(new ContentChangeUseCase.FieldChangeResult("보관 상태", "사용 중", "보관"));
    }
}
