package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.ContinuitySignalResult;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("policy")
class ContinuitySignalAnalyzerTest {

    private static final Instant NOW = Instant.parse("2026-07-19T16:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 20);
    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NEXT_MEMBER_ID =
            UUID.fromString("33333333-3333-3333-3333-444444444444");

    private final ContinuitySignalAnalyzer analyzer = new ContinuitySignalAnalyzer();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @DisplayName("담당자 공백과 14일 안의 후임 공백만 시즌 현지 날짜로 알린다")
    @Test
    void findsUnassignedAndNearSuccessorGaps() {
        Role unassigned = role("진행자", null, null, null, List.of(), null);
        Role endingSoon = role(
                "기록자",
                MEMBER_ID,
                null,
                TODAY.plusDays(14),
                List.of("결정 정리"),
                null
        );
        Role endingLater = role(
                "문제 큐레이터",
                MEMBER_ID,
                null,
                TODAY.plusDays(15),
                List.of("문제 선정"),
                null
        );
        Role sameSuccessor = role(
                "회고 진행자",
                MEMBER_ID,
                MEMBER_ID,
                TODAY.plusDays(14),
                List.of("회고 진행"),
                null
        );

        List<ContinuitySignalResult> signals = analyze(
                season(),
                List.of(unassigned, endingSoon, endingLater, sameSuccessor),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(signals)
                .extracting(ContinuitySignalResult::type)
                .containsExactly(
                        ContinuitySignalType.ROLE_UNASSIGNED,
                        ContinuitySignalType.ROLE_SUCCESSOR_MISSING,
                        ContinuitySignalType.ROLE_SUCCESSOR_MISSING
                );
        assertThat(signals.getFirst().severity()).isEqualTo(ContinuitySignalSeverity.CRITICAL);
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(endingSoon.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.relevantDate()).isEqualTo(TODAY.plusDays(14));
                    assertThat(signal.reason()).contains("14일 뒤");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(sameSuccessor.getId()))
                .singleElement()
                .satisfies(signal ->
                        assertThat(signal.reason()).contains("현재 담당자와 같은", "넘길 수 없습니다"));
    }

    @DisplayName("위험 신호가 있는 역할은 책임과 활성 바통 항목과 자료의 실제 공백을 함께 설명한다")
    @Test
    void explainsPreparationGapsAndIgnoresArchivedItems() {
        Role prepared = role(
                "준비된 역할",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                TODAY.plusDays(30),
                List.of("운영 기록"),
                "기록이 흩어질 수 있습니다"
        );
        HandoffItem completed = handoffItem(prepared.getId(), "운영 기준", true);
        HandoffItem archivedIncomplete = handoffItem(prepared.getId(), "지난 정리", false);
        archivedIncomplete.updateArchive(true, NOW);
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                prepared.getId(),
                "운영 문서",
                "https://example.com/guide",
                null,
                NOW
        );

        Role incomplete = role(
                "준비가 필요한 역할",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                TODAY.plusDays(30),
                List.of(),
                "개인 메모에만 맥락이 있습니다"
        );

        List<ContinuitySignalResult> signals = analyze(
                season(),
                List.of(prepared, incomplete),
                List.of(),
                List.of(),
                List.of(),
                List.of(completed, archivedIncomplete),
                List.of(resource),
                List.of()
        );

        assertThat(signals)
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type())
                            .isEqualTo(ContinuitySignalType.ROLE_PREPARATION_INCOMPLETE);
                    assertThat(signal.roleId()).isEqualTo(incomplete.getId());
                    assertThat(signal.reason())
                            .contains(
                                    "개인 메모에만 맥락이 있습니다",
                                    "책임 목록",
                                    "활성 바통 항목",
                                    "역할 자료"
                            );
                    assertThat(signal.recommendedAction()).contains("보완");
                });
    }

    @DisplayName("서로 다른 활성 회차에서 두 번 지연된 루틴만 반복 지연으로 알린다")
    @Test
    void findsRepeatedOverdueOnlyAcrossActiveRounds() {
        Role owner = role(
                "운영자",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                TODAY.plusDays(30),
                List.of("회차 운영"),
                null
        );
        Routine repeated = routine("질문 수집", owner.getId());
        Routine archivedOnly = routine("회고 정리", owner.getId());
        Routine archivedDefinition = routine("보관된 질문 정리", owner.getId());
        archivedDefinition.updateArchive(true, NOW);
        SeasonRound first = round("1회차", TODAY.minusDays(4));
        SeasonRound second = round("2회차", TODAY.minusDays(2));
        SeasonRound archived = round("보관 회차", TODAY.minusDays(6));
        archived.updateArchive(true, NOW);

        List<RoutineExecution> executions = List.of(
                execution(first, repeated),
                execution(second, repeated),
                execution(first, archivedOnly),
                execution(archived, archivedOnly),
                execution(first, archivedDefinition),
                execution(second, archivedDefinition)
        );

        List<ContinuitySignalResult> signals = analyze(
                season(),
                List.of(owner),
                List.of(repeated, archivedOnly, archivedDefinition),
                List.of(first, second, archived),
                executions,
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(signals)
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type())
                            .isEqualTo(ContinuitySignalType.ROUTINE_REPEATEDLY_OVERDUE);
                    assertThat(signal.routineId()).isEqualTo(repeated.getId());
                    assertThat(signal.reason()).contains("2개 회차");
                });
    }

    @DisplayName("인수인계 준비 중에는 현재 항목을 보고 전달 뒤에는 전달 당시 항목을 본다")
    @Test
    void usesCurrentAndTransferredHandoffReadiness() {
        Role preparingRole = role(
                "진행자",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("모임 진행"),
                "인수인계 맥락이 한 명에게 모여 있습니다"
        );
        RoleHandoff preparing = handoff(preparingRole, TODAY.plusDays(7));
        preparingRole.prepareHandoff(NEXT_MEMBER_ID);

        Role transferredRole = role(
                "기록자",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("결정 기록"),
                null
        );
        RoleHandoff transferred = handoff(transferredRole, TODAY.plusDays(3));
        transferredRole.prepareHandoff(NEXT_MEMBER_ID);
        transferred.transfer(MEMBER_ID, NOW, 1, 1, 1, true);
        HandoffItem currentlyCompleted = handoffItem(
                transferredRole.getId(),
                "전달 뒤 완료로 보이는 항목",
                true
        );

        List<ContinuitySignalResult> signals = analyze(
                season(),
                List.of(preparingRole, transferredRole),
                List.of(),
                List.of(),
                List.of(),
                List.of(currentlyCompleted),
                List.of(),
                List.of(preparing, transferred)
        );

        assertThat(signals)
                .filteredOn(signal ->
                        signal.type() == ContinuitySignalType.HANDOFF_INCOMPLETE)
                .hasSize(2);
        assertThat(signals)
                .noneMatch(signal ->
                        signal.type() == ContinuitySignalType.ROLE_PREPARATION_INCOMPLETE
                                && signal.roleId().equals(preparingRole.getId()));
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(preparingRole.getId()))
                .singleElement()
                .satisfies(signal -> assertThat(signal.reason()).contains("활성 바통 항목이 없습니다"));
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(transferredRole.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.reason()).contains("전달 당시 미완료 항목이 1개였습니다.");
                    assertThat(signal.recommendedAction()).contains("수락");
                });
    }

    @DisplayName("활동 종료한 현재 담당자와 후임과 열린 바통 참여자를 실제 공백으로 알린다")
    @Test
    void findsUnavailableMembersInAssignmentsAndOpenHandoffs() {
        Member inactive = member(NEXT_MEMBER_ID, "김준호");
        inactive.updateDeactivation(true, NOW.minusSeconds(3600));
        Member active = member(MEMBER_ID, "박민서");
        Role inactiveOwner = role(
                "진행자",
                NEXT_MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("모임 진행"),
                null
        );
        Role inactiveSuccessor = role(
                "기록자",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                TODAY.plusDays(14),
                List.of("결정 기록"),
                null
        );
        Role blockedHandoffRole = role(
                "문제 큐레이터",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("문제 선정"),
                null
        );
        RoleHandoff blockedHandoff = handoff(blockedHandoffRole, TODAY.plusDays(30));
        blockedHandoffRole.prepareHandoff(NEXT_MEMBER_ID);
        Role transferredFromInactiveRole = role(
                "회고 진행자",
                NEXT_MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("회고 진행"),
                null
        );
        RoleHandoff transferredFromInactive = RoleHandoff.prepare(
                UUID.randomUUID(),
                TEAM_ID,
                SEASON_ID,
                transferredFromInactiveRole.getId(),
                NEXT_MEMBER_ID,
                MEMBER_ID,
                transferredFromInactiveRole.getAssignmentStartDate(),
                transferredFromInactiveRole.getAssignmentEndDate(),
                TODAY.plusDays(30),
                null,
                NOW.minusSeconds(3600)
        );
        transferredFromInactiveRole.prepareHandoff(MEMBER_ID);
        transferredFromInactive.transfer(NEXT_MEMBER_ID, NOW, 1, 0, 1, false);

        List<ContinuitySignalResult> signals = analyze(
                List.of(inactive, active),
                season(),
                List.of(
                        inactiveOwner,
                        inactiveSuccessor,
                        blockedHandoffRole,
                        transferredFromInactiveRole
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(blockedHandoff, transferredFromInactive)
        );

        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(inactiveOwner.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type()).isEqualTo(ContinuitySignalType.ROLE_UNASSIGNED);
                    assertThat(signal.reason()).contains("김준호", "활동을 종료");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(inactiveSuccessor.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type())
                            .isEqualTo(ContinuitySignalType.ROLE_SUCCESSOR_MISSING);
                    assertThat(signal.reason()).contains("김준호", "활동을 종료");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(blockedHandoffRole.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type()).isEqualTo(ContinuitySignalType.HANDOFF_INCOMPLETE);
                    assertThat(signal.severity()).isEqualTo(ContinuitySignalSeverity.CRITICAL);
                    assertThat(signal.reason()).contains("다음 담당자", "활동 종료");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(transferredFromInactiveRole.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.title()).contains("현재 담당 공백", "수락 대기");
                    assertThat(signal.severity()).isEqualTo(ContinuitySignalSeverity.CRITICAL);
                    assertThat(signal.reason()).contains("활동을 종료", "즉시 수락");
                    assertThat(signal.recommendedAction()).contains("즉시 수락");
                });
    }

    @DisplayName("후임만 정했거나 전달과 수락이 남은 가까운 바통은 준비도와 무관하게 알린다")
    @Test
    void findsMissingAndPendingHandoffTransitions() {
        Role notStarted = role(
                "기록자",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                TODAY.plusDays(14),
                List.of("결정 기록"),
                null
        );
        Role readyToTransfer = role(
                "진행자",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("모임 진행"),
                null
        );
        RoleHandoff preparing = handoff(readyToTransfer, TODAY.plusDays(7));
        readyToTransfer.prepareHandoff(NEXT_MEMBER_ID);
        HandoffItem completed = handoffItem(readyToTransfer.getId(), "운영 기준", true);

        Role awaitingAcceptance = role(
                "문제 큐레이터",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("문제 선정"),
                null
        );
        RoleHandoff transferred = handoff(awaitingAcceptance, TODAY.minusDays(1));
        awaitingAcceptance.prepareHandoff(NEXT_MEMBER_ID);
        transferred.transfer(MEMBER_ID, NOW, 1, 0, 1, false);

        Role outsideBoundary = role(
                "회고 진행자",
                MEMBER_ID,
                null,
                TODAY.plusDays(30),
                List.of("회고 진행"),
                null
        );
        RoleHandoff later = handoff(outsideBoundary, TODAY.plusDays(8));
        outsideBoundary.prepareHandoff(NEXT_MEMBER_ID);
        Role coverageGap = role(
                "자료 관리자",
                MEMBER_ID,
                null,
                TODAY,
                List.of("자료 정리"),
                null
        );
        RoleHandoff gapHandoff = handoff(coverageGap, TODAY.plusDays(30));
        coverageGap.prepareHandoff(NEXT_MEMBER_ID);

        List<ContinuitySignalResult> signals = analyze(
                season(),
                List.of(
                        notStarted,
                        readyToTransfer,
                        awaitingAcceptance,
                        outsideBoundary,
                        coverageGap
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(completed),
                List.of(),
                List.of(preparing, transferred, later, gapHandoff)
        );

        assertThat(signals)
                .filteredOn(signal -> signal.type() == ContinuitySignalType.HANDOFF_INCOMPLETE)
                .extracting(ContinuitySignalResult::roleId)
                .containsExactlyInAnyOrder(
                        notStarted.getId(),
                        readyToTransfer.getId(),
                        awaitingAcceptance.getId(),
                        coverageGap.getId()
                );
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(notStarted.getId()))
                .singleElement()
                .satisfies(signal -> assertThat(signal.title()).contains("준비 미시작"));
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(readyToTransfer.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.title()).contains("전달 대기");
                    assertThat(signal.recommendedAction()).contains("전달");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(awaitingAcceptance.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.title()).contains("수락 대기");
                    assertThat(signal.recommendedAction()).contains("수락");
                });
        assertThat(signals)
                .filteredOn(signal -> signal.roleId().equals(coverageGap.getId()))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.title()).contains("담당 공백 예정");
                    assertThat(signal.reason()).contains(TODAY.toString(), "29일의 담당 공백");
                    assertThat(signal.recommendedAction()).contains("취소", "다시 준비");
                });
    }

    @DisplayName("종료한 시즌은 과거 기록에 행동 가능한 연속성 경고를 만들지 않는다")
    @Test
    void suppressesSignalsForEndedSeason() {
        Season endedSeason = season();
        endedSeason.updateEnding(true, NOW);
        Role unassigned = role("지난 역할", null, null, null, List.of(), "담당자가 없습니다");

        assertThat(analyze(
                endedSeason,
                List.of(unassigned),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        )).isEmpty();
    }

    private List<ContinuitySignalResult> analyze(
            Season season,
            List<Role> roles,
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            List<HandoffItem> handoffItems,
            List<RoleResource> resources,
            List<RoleHandoff> roleHandoffs
    ) {
        return analyze(
                List.of(
                        member(MEMBER_ID, "박민서"),
                        member(NEXT_MEMBER_ID, "김준호")
                ),
                season,
                roles,
                routines,
                rounds,
                executions,
                handoffItems,
                resources,
                roleHandoffs
        );
    }

    private List<ContinuitySignalResult> analyze(
            List<Member> members,
            Season season,
            List<Role> roles,
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            List<HandoffItem> handoffItems,
            List<RoleResource> resources,
            List<RoleHandoff> roleHandoffs
    ) {
        return analyzer.analyze(
                clock,
                season,
                members,
                roles,
                routines,
                rounds,
                executions,
                handoffItems,
                resources,
                roleHandoffs
        );
    }

    private Member member(UUID id, String name) {
        return Member.create(id, TEAM_ID, name);
    }

    private Season season() {
        return Season.create(
                SEASON_ID,
                TEAM_ID,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30),
                "Asia/Seoul"
        );
    }

    private Role role(
            String name,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
        return Role.create(
                UUID.randomUUID(),
                TEAM_ID,
                SEASON_ID,
                name,
                name + " 역할의 목적",
                currentMemberId,
                nextMemberId,
                currentMemberId == null ? null : TODAY.minusDays(10),
                assignmentEndDate,
                responsibilities,
                risk
        );
    }

    private Routine routine(String title, UUID roleId) {
        return Routine.create(
                UUID.randomUUID(),
                SEASON_ID,
                title,
                RoutinePhase.BEFORE,
                "모임 당일",
                roleId,
                "정해진 일을 수행합니다",
                0,
                LocalTime.MIDNIGHT
        );
    }

    private SeasonRound round(String name, LocalDate meetingDate) {
        return SeasonRound.create(UUID.randomUUID(), SEASON_ID, name, meetingDate);
    }

    private RoutineExecution execution(SeasonRound round, Routine routine) {
        return RoutineExecution.snapshot(
                UUID.randomUUID(),
                round.getId(),
                routine,
                round.getMeetingDate(),
                ZoneId.of("Asia/Seoul")
        );
    }

    private HandoffItem handoffItem(UUID roleId, String label, boolean completed) {
        return HandoffItem.create(
                UUID.randomUUID(),
                roleId,
                label,
                HandoffCategory.RESPONSIBILITY,
                completed,
                NOW
        );
    }

    private RoleHandoff handoff(Role role, LocalDate incomingStartDate) {
        return RoleHandoff.prepare(
                UUID.randomUUID(),
                TEAM_ID,
                SEASON_ID,
                role.getId(),
                MEMBER_ID,
                NEXT_MEMBER_ID,
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate(),
                incomingStartDate,
                null,
                NOW.minusSeconds(3600)
        );
    }
}
