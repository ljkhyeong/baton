package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerifyResourceCommand;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.ConfigureReviewScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateRoleResourceCommand;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.workspace.ResourceVerificationStatus;
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
class ResourceVerificationUseCaseTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
            .withDatabaseName("resource_verification_test").withUsername("baton").withPassword("password");
    @Autowired WorkspaceLifecycleUseCase lifecycle;
    @Autowired WorkspacePeopleUseCase people;
    @Autowired WorkspaceRecordsUseCase records;
    @Autowired RoundAdministrationUseCase memberships;
    @Autowired ResourceVerificationUseCase verifications;
    @Autowired IdentityRepository identities;
    @Autowired Clock clock;

    @Test
    @DisplayName("자료 확인은 연결된 구성원의 신원과 버전을 보존하고 변경·보관·활동 종료를 구분한다")
    void preservesVerificationHistoryAndRejectsStaleConfirmation() {
        var workspace = lifecycle.createWorkspace(UUID.randomUUID().toString(), "pilot-operator-key-0000000000000001",
                new CreateWorkspaceCommand("자료 검토 팀", "시즌", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), List.of("박민서")));
        var team = workspace.teamId();
        var season = workspace.seasonId();
        var key = workspace.accessKey();
        var member = lifecycle.getWorkspace(team, season, key).members().getFirst();
        var role = people.createRole(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleCommand("기록 담당", "자료를 관리합니다", null, null, null, null, List.of(), null));
        var resource = records.createRoleResource(team, season, UUID.randomUUID().toString(), key,
                new CreateRoleResourceCommand(role.id(), "운영 안내", "https://example.com/guide", null));
        var account = identities.saveAccount(Account.create(UUID.randomUUID(), "박민서 계정", clock.instant()));
        var command = new VerifyResourceCommand(0, ResourceVerificationStatus.CONFIRMED, "접근과 내용 확인");
        assertThatThrownBy(() -> verifications.verify(team, season, resource.id(), key, account.getId(), command))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        memberships.claimMembership(new ClaimMembershipCommand(account.getId(), team, season, member.id(), key));
        var emptySchedule = verifications.getSchedule(team, season, resource.id(), key);
        assertThat(emptySchedule.version()).isEqualTo(-1);
        var plan = verifications.configureSchedule(team, season, resource.id(), key, account.getId(),
                new ConfigureReviewScheduleCommand(-1, 30, emptySchedule.today()));
        assertThat(plan.reviewDue()).isTrue();
        assertThat(verifications.getSchedule(team, season, resource.id(), key)).isEqualTo(plan);
        var due = verifications.getDueReviews(team, season, key);
        assertThat(due.resources()).extracting(ResourceVerificationUseCase.DueReviewResult::resourceId).containsExactly(resource.id());
        assertThat(due.resources().getFirst().memberId()).isNull();
        assertThatThrownBy(() -> verifications.getDueReviews(team, season, "wrong-key"))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> verifications.configureSchedule(team, season, resource.id(), key, account.getId(),
                new ConfigureReviewScheduleCommand(-1, 7, emptySchedule.today()))).isInstanceOf(WorkspaceContentConflictException.class);
        var history = verifications.verify(team, season, resource.id(), key, account.getId(), command);
        var nextPlan = verifications.getSchedule(team, season, resource.id(), key);
        assertThat(nextPlan.nextReviewOn()).isEqualTo(plan.today().plusDays(30));
        assertThat(nextPlan.reviewDue()).isFalse();
        assertThat(verifications.getDueReviews(team, season, key).resources()).isEmpty();
        assertThat(nextPlan.version()).isGreaterThan(plan.version());
        assertThat(history.verifications()).hasSize(1);
        var confirmed = history.verifications().getFirst();
        assertThat(confirmed.memberId()).isEqualTo(member.id());
        assertThat(confirmed.memberName()).isEqualTo("박민서");
        assertThat(confirmed.verifiedAt()).isNotNull();
        assertThat(confirmed.current()).isTrue();
        records.updateRoleResource(team, season, resource.id(), key,
                new UpdateRoleResourceCommand(role.id(), resource.title(), resource.url(), "설명 정정"));
        var changed = verifications.getHistory(team, season, resource.id(), key);
        assertThat(changed.verifications().getFirst().current()).isFalse();
        assertThatThrownBy(() -> verifications.verify(team, season, resource.id(), key, account.getId(), command))
                .isInstanceOf(WorkspaceContentConflictException.class);
        var current = new VerifyResourceCommand(changed.resourceVersion(), ResourceVerificationStatus.NEEDS_UPDATE, "권한 요청 필요");
        assertThat(verifications.verify(team, season, resource.id(), key, account.getId(), current).verifications()).hasSize(2);
        assertThat(verifications.getSchedule(team, season, resource.id(), key).nextReviewOn()).isEqualTo(nextPlan.nextReviewOn());
        var disabled = verifications.configureSchedule(team, season, resource.id(), key, account.getId(),
                new ConfigureReviewScheduleCommand(nextPlan.version(), null, null));
        assertThat(disabled.nextReviewOn()).isNull(); assertThat(disabled.reviewDue()).isFalse();
        assertThat(disabled.version()).isGreaterThan(nextPlan.version());
        assertThat(verifications.getSchedule(team, season, resource.id(), key)).isEqualTo(disabled);
        people.updateMemberDeactivation(team, season, member.id(), key, true);
        assertThatThrownBy(() -> verifications.verify(team, season, resource.id(), key, account.getId(), current))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        people.updateMemberDeactivation(team, season, member.id(), key, false);
        verifications.configureSchedule(team, season, resource.id(), key, account.getId(),
                new ConfigureReviewScheduleCommand(disabled.version(), 7, plan.today()));
        records.updateRoleResourceArchive(team, season, resource.id(), key, true);
        assertThat(verifications.getDueReviews(team, season, key).resources()).isEmpty();
        assertThatThrownBy(() -> verifications.verify(team, season, resource.id(), key, account.getId(), current))
                .isInstanceOf(WorkspaceContentConflictException.class);
        assertThat(verifications.getHistory(team, season, resource.id(), key).verifications()).hasSize(2);
        assertThatThrownBy(() -> verifications.getHistory(team, season, resource.id(), "wrong-key"))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
    }
}
