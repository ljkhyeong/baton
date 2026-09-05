package com.personal.baton.application.brief;

import com.personal.baton.application.brief.error.BriefAccessDeniedException;
import com.personal.baton.application.brief.error.BriefEditionNotFoundException;
import com.personal.baton.application.brief.error.BriefGenerationBlockedException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.ClaimResult;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.DeliveryBoundary;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.GenerationTarget;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefServiceClient.Result;
import com.personal.baton.application.brief.port.out.BriefServiceClient;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



class BriefApplicationServiceTest {

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
    private final BriefServiceClient client = mock(BriefServiceClient.class);
    private final BriefEditionGenerationExecutionPort executionPort = mock(
            BriefEditionGenerationExecutionPort.class
    );
    private final BriefContinuitySignalStorePort signalStore = mock(BriefContinuitySignalStorePort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private BriefApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BriefApplicationService(
                workspaceAccess,
                workspaceRepository,
                roundRepository,
                client,
                executionPort,
                clock,
                signalStore,
                true
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

    @Test
    @DisplayName("해소 요약의 이번 주는 시즌 시간대에서 정하고 권한 거부 전에 외부를 조회하지 않는다")
    void resolvesCurrentWeekInSeasonZone() {
        var scope = new BriefAttentionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        var season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("America/Los_Angeles"));
        when(workspaceAccess.verifyRead(TEAM_ID, SEASON_ID, "workspace-access-key")).thenReturn(season);
        var boundaryService = new BriefApplicationService(workspaceAccess, workspaceRepository, roundRepository,
                client, executionPort, Clock.fixed(Instant.parse("2026-08-31T01:00:00Z"), ZoneOffset.UTC), signalStore, true);
        boundaryService.summarizeWeeklyResolutions(scope);
        verify(client).summarizeWeeklyResolutions(TEAM_ID, SEASON_ID, LocalDate.parse("2026-08-24"), season.getZoneId());
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID)).thenReturn(Optional.empty());
        org.mockito.Mockito.clearInvocations(client);
        assertThatThrownBy(() -> boundaryService.summarizeWeeklyResolutions(scope)).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findPreviousWeekEdition(new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key"), EDITION_ID))
                .isInstanceOf(BriefAccessDeniedException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("지난주 조회는 선택한 브리프의 주차와 시간대를 유지하고 잘못된 결과나 없는 주차를 구분한다")
    void findsPreviousWeekFromSavedEdition() {
        var scope = new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        var selected = snapshot(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-09"), ZoneId.of("America/New_York"));
        var previous = snapshot(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), selected.zoneId());
        when(client.findEdition(EDITION_ID)).thenReturn(Result.completed(selected, "selected", false));
        when(client.findLatestEditionForWeek(TEAM_ID, SEASON_ID, previous.weekStart(), selected.zoneId()))
                .thenReturn(Result.completed(previous, "previous", false));
        assertThat(service.findPreviousWeekEdition(scope, EDITION_ID).edition()).isEqualTo(previous);
        when(client.findLatestEditionForWeek(TEAM_ID, SEASON_ID, previous.weekStart(), selected.zoneId()))
                .thenReturn(Result.completed(selected, "wrong-week", false));
        assertThatThrownBy(() -> service.findPreviousWeekEdition(scope, EDITION_ID)).isInstanceOf(BriefIntegrationConfigurationException.class);
        when(client.findLatestEditionForWeek(TEAM_ID, SEASON_ID, previous.weekStart(), selected.zoneId()))
                .thenReturn(Result.failure(BriefServiceClient.Outcome.NOT_FOUND, "missing"));
        assertThatThrownBy(() -> service.findPreviousWeekEdition(scope, EDITION_ID)).isInstanceOf(BriefEditionNotFoundException.class);
        verify(client, never()).findLatestEdition(any(), any());
    }

    @Test
    @DisplayName("식별자를 아는 다른 팀 브리프는 조회와 비교에서 감추고 비교 호출을 막는다")
    void rejectsForeignEditionBeforeComparison() {
        var scope = new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        when(client.findEdition(EDITION_ID)).thenReturn(Result.completed(snapshot(UUID.randomUUID(), SEASON_ID), "etag", false));
        assertThatThrownBy(() -> service.findEdition(scope, EDITION_ID))
                .isInstanceOf(BriefEditionNotFoundException.class);
        assertThatThrownBy(() -> service.compareEditions(scope, EDITION_ID, UUID.randomUUID()))
                .isInstanceOf(BriefEditionNotFoundException.class);
        assertThatThrownBy(() -> service.findEditionDeliveryStatus(scope, EDITION_ID))
                .isInstanceOf(BriefEditionNotFoundException.class);
        assertThatThrownBy(() -> service.findPreviousWeekEdition(scope, EDITION_ID))
                .isInstanceOf(BriefEditionNotFoundException.class);
        verify(client, never()).findLatestEditionForWeek(any(), any(), any(), any());
        verifyNoInteractions(executionPort);
        verify(client, never()).compareEditions(any(), any());
        when(client.findEdition(EDITION_ID)).thenReturn(Result.completed(snapshot(TEAM_ID, SEASON_ID), "etag", false));
        UUID foreignId = UUID.randomUUID();
        when(client.findEdition(foreignId)).thenReturn(Result.completed(snapshot(TEAM_ID, UUID.randomUUID()), "etag", false));
        assertThatThrownBy(() -> service.compareEditions(scope, EDITION_ID, foreignId))
                .isInstanceOf(BriefEditionNotFoundException.class);
        verify(client, never()).compareEditions(any(), any());
        service.compareEditions(scope, EDITION_ID, EDITION_ID);
        verify(client).compareEditions(EDITION_ID, EDITION_ID);
    }

    @Test
    @DisplayName("브리프 추가 전달 안내는 생성 근거 없음과 추가 전달 유무를 구분한다")
    void reportsEditionDeliveryEvidence() {
        var scope = new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        when(client.findEdition(EDITION_ID)).thenReturn(Result.completed(snapshot(TEAM_ID, SEASON_ID), "etag", false));
        when(executionPort.findAdditionalDeliveries(TEAM_ID, SEASON_ID, EDITION_ID)).thenReturn(Optional.empty());
        var unknown = service.findEditionDeliveryStatus(scope, EDITION_ID);
        assertThat(unknown.editionId()).isEqualTo(EDITION_ID);
        assertThat(unknown.status()).isEqualTo(BriefEditionDeliveryStatus.Status.UNKNOWN);
        assertThat(unknown.checkedAt()).isEqualTo(NOW);
        when(executionPort.findAdditionalDeliveries(TEAM_ID, SEASON_ID, EDITION_ID)).thenReturn(Optional.of(false));
        assertThat(service.findEditionDeliveryStatus(scope, EDITION_ID).status())
                .isEqualTo(BriefEditionDeliveryStatus.Status.NO_ADDITIONAL_DELIVERIES);
        when(executionPort.findAdditionalDeliveries(TEAM_ID, SEASON_ID, EDITION_ID)).thenReturn(Optional.of(true));
        assertThat(service.findEditionDeliveryStatus(scope, EDITION_ID).status())
                .isEqualTo(BriefEditionDeliveryStatus.Status.ADDITIONAL_DELIVERIES);
        verify(executionPort, never()).claim(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("원본 참조는 저장된 신호와 유형·시즌이 일치할 때만 현재 업무에 연결한다")
    void resolvesOnlyMatchingSourceIdentity() {
        var scope = new BriefAttentionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        var identity = new BriefSourceContext.Identity(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "baton-continuity:" + EDITION_ID);
        var wrongType = new BriefSourceContext.Identity(BriefAttentionPage.EventType.HANDOFF_INCOMPLETE, identity.sourceReference());
        var legacy = new BriefSourceContext.Identity(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "role:" + MEMBER_ID);
        var role = mock(Role.class);
        when(role.getId()).thenReturn(MEMBER_ID); when(role.getTeamId()).thenReturn(TEAM_ID); when(role.getSeasonId()).thenReturn(SEASON_ID);
        when(role.getName()).thenReturn("현재 홍보 담당");
        when(workspaceRepository.findRoleById(MEMBER_ID)).thenReturn(Optional.of(role));
        when(signalStore.findByIds(TEAM_ID, SEASON_ID, List.of(EDITION_ID))).thenReturn(List.of(new BriefContinuitySignalState(
                EDITION_ID, ContinuitySignalType.ROLE_UNASSIGNED, MEMBER_ID,
                ContinuitySignalSeverity.WARNING, BriefContinuityEvent.State.ACTIVE, 1)));
        var contexts = service.resolveSources(scope, List.of(identity, wrongType, legacy));
        assertThat(contexts.getFirst().target().title()).isEqualTo("현재 홍보 담당");
        assertThat(contexts.get(1).target()).isNull(); assertThat(contexts.get(2).target()).isNull();
        when(role.getSeasonId()).thenReturn(UUID.randomUUID());
        assertThat(service.resolveSources(scope, List.of(identity)).getFirst().target()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("생성 준비 조회는 실패·대기·진행 상태를 구분하고 만료 실행은 다시 요청할 수 있다")
    void reportsReadinessWithoutClaimOrNetworkCall() {
        var scope = new BriefAttentionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyRead(TEAM_ID, SEASON_ID, "workspace-access-key")).thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID)).thenReturn(new DeliveryBoundary(17, 2, 1, NOW));
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.DELIVERY_FAILED);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID)).thenReturn(new DeliveryBoundary(17, 2, 0, NOW));
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.DELIVERY_PENDING);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID)).thenReturn(new DeliveryBoundary(17, 0, 0, NOW));
        when(executionPort.findExecutionState(any())).thenReturn(Optional.of(new BriefEditionGenerationExecutionPort.ExecutionState("PROCESSING", NOW.plusSeconds(1))));
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.GENERATING);
        when(executionPort.findExecutionState(any())).thenReturn(Optional.of(new BriefEditionGenerationExecutionPort.ExecutionState("PROCESSING", NOW)));
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.READY);
        when(executionPort.findExecutionState(any())).thenReturn(Optional.of(new BriefEditionGenerationExecutionPort.ExecutionState("PERMANENT_FAILURE", null)));
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.GENERATION_FAILED);
        when(season.isEnded()).thenReturn(true);
        assertThat(service.findGenerationReadiness(scope).status()).isEqualTo(BriefGenerationReadiness.Status.SEASON_ENDED);
        verify(executionPort, never()).claim(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("활동 종료된 계정 구성원은 BRIEF 요약과 목록 및 상태 전이를 호출할 수 없다")
    void deniesAttentionReadsBeforeExternalCall() {
        Member member = workspaceRepository.findMemberById(MEMBER_ID).orElseThrow();
        when(member.isActive()).thenReturn(false);
        var scope = new BriefAttentionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        assertThatThrownBy(() -> service.summarizeAttention(scope)).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findAttentionItems(scope,
                new BriefAttentionPage.Filter(BriefAttentionPage.Status.ACTIVE, null, null, null, 20)))
                .isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findAttentionTransitions(scope,
                new BriefAttentionTransitions.Query(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "role:1", null, 20)))
                .isInstanceOf(BriefAccessDeniedException.class);
        var editionScope = new LatestEditionQuery(ACCOUNT_ID, TEAM_ID, SEASON_ID, "workspace-access-key");
        assertThatThrownBy(() -> service.findEditionHistory(editionScope, new BriefEditionHistory.Query(null, 20))).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findEdition(editionScope, EDITION_ID)).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findEditionDeliveryStatus(editionScope, EDITION_ID)).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.compareEditions(editionScope, EDITION_ID, EDITION_ID)).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.resolveSources(scope, List.of())).isInstanceOf(BriefAccessDeniedException.class);
        assertThatThrownBy(() -> service.findGenerationReadiness(scope)).isInstanceOf(BriefAccessDeniedException.class);
        verifyNoInteractions(client, signalStore, executionPort);
    }

    @DisplayName("시즌 시간대의 이번 주 월요일과 완료된 전달 watermark로 에디션을 생성한다")
    @Test
    void generatesEditionForAuthoritativeWeekAndZone() {
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key"))
                .thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID))
                .thenReturn(new DeliveryBoundary(17, 0, 0, null));
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

    @DisplayName("생성 응답의 주차나 시간대가 요청과 다르면 영구 실패로 기록하고 성공 저장을 막는다")
    @ParameterizedTest
    @CsvSource({
            "2026-08-17, Asia/Seoul",
            "2026-08-24, America/New_York"
    })
    void rejectsGenerationResponseOutsideRequestedWeekOrZone(
            String responseWeekStart,
            String responseZoneId
    ) {
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key"))
                .thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID))
                .thenReturn(new DeliveryBoundary(17, 0, 0, null));
        when(executionPort.claim(any(), eq(true), eq(NOW), any()))
                .thenReturn(new ClaimResult.Claimed(EXECUTION_ID, LEASE_TOKEN));
        when(client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        )).thenReturn(Result.completed(
                snapshot(
                        TEAM_ID,
                        SEASON_ID,
                        LocalDate.parse(responseWeekStart),
                        ZoneId.of(responseZoneId)
                ),
                "\"brief-etag\"",
                true
        ));
        when(executionPort.markPermanentFailure(
                EXECUTION_ID,
                LEASE_TOKEN,
                NOW,
                "BRIEF_SCOPE_MISMATCH"
        )).thenReturn(true);

        assertThatThrownBy(() -> service.generateEdition(command()))
                .isInstanceOf(BriefIntegrationConfigurationException.class);

        verify(executionPort).markPermanentFailure(
                EXECUTION_ID,
                LEASE_TOKEN,
                NOW,
                "BRIEF_SCOPE_MISMATCH"
        );
        verify(executionPort, never()).markSucceeded(
                EXECUTION_ID,
                LEASE_TOKEN,
                NOW,
                EDITION_ID,
                3,
                17,
                "\"brief-etag\"",
                true
        );
    }

    @DisplayName("완료되지 않은 BRIEF 이벤트가 있으면 실행 기록만 남기고 생성하지 않는다")
    @Test
    void blocksGenerationUntilDeliveryCompletes() {
        Season season = mock(Season.class);
        when(season.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(workspaceAccess.verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key"))
                .thenReturn(season);
        when(executionPort.findDeliveryBoundary(TEAM_ID, SEASON_ID))
                .thenReturn(new DeliveryBoundary(18, 1, 0, null));
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
        return snapshot(
                teamId,
                seasonId,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );
    }

    private BriefEditionSnapshot snapshot(
            UUID teamId,
            UUID seasonId,
            LocalDate weekStart,
            ZoneId zoneId
    ) {
        return new BriefEditionSnapshot(
                EDITION_ID,
                teamId,
                seasonId,
                3,
                weekStart,
                zoneId,
                weekStart.atStartOfDay(zoneId).toInstant(),
                weekStart.plusDays(7).atStartOfDay(zoneId).toInstant(),
                17,
                NOW,
                1,
                List.of()
        );
    }
}
