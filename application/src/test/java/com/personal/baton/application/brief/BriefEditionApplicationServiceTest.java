package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.brief.error.BriefGenerationBlockedException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.ClaimResult;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.DeliveryBoundary;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.GenerationTarget;
import com.personal.baton.application.brief.port.out.BriefEditionServiceClient;
import com.personal.baton.application.brief.port.out.BriefEditionServiceClient.Result;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BriefEditionApplicationServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002601"
    );
    private static final UUID TEAM_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002602"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002603"
    );
    private static final UUID MEMBER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002604"
    );
    private static final UUID EXECUTION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002605"
    );
    private static final UUID LEASE_TOKEN = UUID.fromString(
            "00000000-0000-0000-0000-000000002606"
    );
    private static final UUID EDITION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002607"
    );
    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");

    private final VerifyWorkspaceAccessUseCase workspaceAccess = mock(
            VerifyWorkspaceAccessUseCase.class
    );
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final RoundAuthorizationRepository roundRepository = mock(
            RoundAuthorizationRepository.class
    );
    private final BriefEditionServiceClient client = mock(BriefEditionServiceClient.class);
    private final BriefEditionGenerationExecutionPort executionPort = mock(
            BriefEditionGenerationExecutionPort.class
    );
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private BriefEditionApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BriefEditionApplicationService(
                workspaceAccess,
                workspaceRepository,
                roundRepository,
                client,
                executionPort,
                clock
        );
        AccountTeamMembership membership = mock(AccountTeamMembership.class);
        Member member = mock(Member.class);
        when(membership.getMemberId()).thenReturn(MEMBER_ID);
        when(member.getTeamId()).thenReturn(TEAM_ID);
        when(member.isActive()).thenReturn(true);
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership));
        when(workspaceRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(member));
    }

    @DisplayName("시즌 시간대의 이번 주 월요일과 완료된 전달 watermark로 에디션을 생성한다")
    @Test
    void generatesEditionForAuthoritativeWeekAndZone() {
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key"))
                .thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID))
                .thenReturn(new DeliveryBoundary(17, true));
        when(executionPort.claim(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new ClaimResult.Claimed(EXECUTION_ID, LEASE_TOKEN));
        when(client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        )).thenReturn(Result.completed(snapshot(TEAM_ID, SEASON_ID), "\"brief-etag\"", true));
        when(executionPort.markSucceeded(
                EXECUTION_ID,
                LEASE_TOKEN,
                NOW,
                EDITION_ID,
                3,
                17,
                "\"brief-etag\"",
                true
        )).thenReturn(true);

        var result = service.generateEdition(command());

        assertThat(result.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(result.deliveryWatermark()).isEqualTo(17);
        assertThat(result.editionId()).isEqualTo(EDITION_ID);
        ArgumentCaptor<GenerationTarget> target = ArgumentCaptor.forClass(
                GenerationTarget.class
        );
        verify(executionPort).claim(
                target.capture(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(target.getValue().weekStart()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(target.getValue().zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @DisplayName("완료되지 않은 BRIEF 이벤트가 있으면 실행 기록만 남기고 생성하지 않는다")
    @Test
    void blocksGenerationUntilDeliveryCompletes() {
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key"))
                .thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID))
                .thenReturn(new DeliveryBoundary(18, false));
        when(executionPort.claim(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new ClaimResult.DeliveryIncomplete(EXECUTION_ID));

        assertThatThrownBy(() -> service.generateEdition(command()))
                .isInstanceOf(BriefGenerationBlockedException.class);

        verify(client, never()).generateEdition(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @DisplayName("BRIEF 최신 응답의 작업공간과 시즌이 권한 범위와 다르면 노출하지 않는다")
    @Test
    void rejectsLatestEditionOutsideAuthorizedScope() {
        when(client.findLatestEdition(TEAM_ID, SEASON_ID)).thenReturn(Result.completed(
                snapshot(UUID.randomUUID(), SEASON_ID),
                "\"brief-etag\"",
                false
        ));

        assertThatThrownBy(() -> service.findLatestEdition(new LatestEditionQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                "workspace-access-key"
        ))).isInstanceOf(BriefIntegrationConfigurationException.class);

        verify(workspaceAccess).verifyRead(TEAM_ID, SEASON_ID, "workspace-access-key");
    }

    private GenerateEditionCommand command() {
        return new GenerateEditionCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                "workspace-access-key"
        );
    }

    private BriefEditionSnapshot snapshot(UUID teamId, UUID seasonId) {
        return new BriefEditionSnapshot(
                EDITION_ID,
                teamId,
                seasonId,
                3,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-23T15:00:00Z"),
                Instant.parse("2026-08-30T15:00:00Z"),
                17,
                NOW,
                1,
                List.of()
        );
    }
}
