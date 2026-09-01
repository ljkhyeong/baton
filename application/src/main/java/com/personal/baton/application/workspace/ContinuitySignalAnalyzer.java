package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.ContinuitySignalResult;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
final class ContinuitySignalAnalyzer {

    static final int SUCCESSOR_WARNING_DAYS = 14;
    static final int HANDOFF_WARNING_DAYS = 7;
    static final int REPEATED_OVERDUE_ROUNDS = 2;

    List<ContinuitySignalResult> analyze(
            Clock clock,
            Season season,
            List<Member> members,
            List<Role> roles,
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            List<HandoffItem> handoffItems,
            List<RoleResource> resources,
            List<RoleHandoff> roleHandoffs
    ) {
        Objects.requireNonNull(clock, "현재 시각 기준은 필수입니다");
        Objects.requireNonNull(season, "시즌은 필수입니다");
        Objects.requireNonNull(members, "구성원 목록은 필수입니다");
        Objects.requireNonNull(roles, "역할 목록은 필수입니다");
        Objects.requireNonNull(routines, "루틴 목록은 필수입니다");
        Objects.requireNonNull(rounds, "회차 목록은 필수입니다");
        Objects.requireNonNull(executions, "루틴 실행 목록은 필수입니다");
        Objects.requireNonNull(handoffItems, "바통 항목 목록은 필수입니다");
        Objects.requireNonNull(resources, "역할 자료 목록은 필수입니다");
        Objects.requireNonNull(roleHandoffs, "역할 바통 목록은 필수입니다");

        if (season.isEnded()) {
            return List.of();
        }

        Instant now = clock.instant();
        Clock snapshotClock = Clock.fixed(now, ZoneOffset.UTC);
        ZoneId zoneId = season.getZoneId();
        LocalDate today = now.atZone(zoneId).toLocalDate();
        Map<UUID, Member> membersById = membersById(members);
        Set<UUID> activeMemberIds = activeMemberIds(members);
        Map<UUID, List<HandoffItem>> activeItemsByRole = activeItemsByRole(handoffItems);
        Map<UUID, Integer> resourceCountsByRole = countsByRole(resources);
        List<ContinuitySignalResult> signals = new ArrayList<>();

        Set<UUID> rolesWithHandoffSignals = addHandoffSignals(
                signals,
                roles,
                activeItemsByRole,
                roleHandoffs,
                membersById,
                activeMemberIds,
                today
        );
        addRoleSignals(
                signals,
                season,
                roles,
                membersById,
                activeMemberIds,
                activeItemsByRole,
                resourceCountsByRole,
                rolesWithHandoffSignals,
                today
        );
        addRepeatedOverdueSignals(
                signals,
                routines,
                rounds,
                executions,
                snapshotClock,
                zoneId
        );
        return signals.stream()
                .sorted(continuitySignalOrder())
                .toList();
    }

    private void addRoleSignals(
            List<ContinuitySignalResult> signals,
            Season season,
            List<Role> roles,
            Map<UUID, Member> membersById,
            Set<UUID> activeMemberIds,
            Map<UUID, List<HandoffItem>> activeItemsByRole,
            Map<UUID, Integer> resourceCountsByRole,
            Set<UUID> rolesWithHandoffSignals,
            LocalDate today
    ) {
        for (Role role : roles) {
            boolean coveredByHandoffSignal = rolesWithHandoffSignals.contains(role.getId());
            boolean currentMemberActive = activeMemberIds.contains(role.getCurrentMemberId());
            if (!currentMemberActive && !coveredByHandoffSignal) {
                signals.add(new ContinuitySignalResult(
                        ContinuitySignalType.ROLE_UNASSIGNED,
                        today.isBefore(season.getStartDate())
                                ? ContinuitySignalSeverity.WARNING
                                : ContinuitySignalSeverity.CRITICAL,
                        role.getId(),
                        null,
                        role.getName() + " 담당자 공백",
                        currentMemberGapReason(
                                role,
                                membersById.get(role.getCurrentMemberId())
                        ),
                        "활동 중인 구성원과 담당 기간을 지정하세요.",
                        null
                ));
            }

            LocalDate assignmentEndDate = role.getAssignmentEndDate();
            Member nextMember = membersById.get(role.getNextMemberId());
            boolean nextMemberEligible = hasEligibleNextMember(role, activeMemberIds);
            if (currentMemberActive
                    && !coveredByHandoffSignal
                    && !nextMemberEligible
                    && assignmentEndDate != null
                    && !assignmentEndDate.isAfter(today.plusDays(SUCCESSOR_WARNING_DAYS))) {
                signals.add(new ContinuitySignalResult(
                        ContinuitySignalType.ROLE_SUCCESSOR_MISSING,
                        assignmentEndDate.isAfter(today)
                                ? ContinuitySignalSeverity.WARNING
                                : ContinuitySignalSeverity.CRITICAL,
                        role.getId(),
                        null,
                        role.getName() + " 후임 공백",
                        successorReason(role, nextMember, assignmentEndDate, today),
                        "현재 담당자와 다른 활동 중인 다음 담당자를 정하고 역할 바통 준비를 시작하세요.",
                        assignmentEndDate
                ));
            }

            addPreparationSignal(
                    signals,
                    role,
                    activeItemsByRole.getOrDefault(role.getId(), List.of()),
                    resourceCountsByRole.getOrDefault(role.getId(), 0),
                    rolesWithHandoffSignals
            );
        }
    }

    private void addPreparationSignal(
            List<ContinuitySignalResult> signals,
            Role role,
            List<HandoffItem> activeItems,
            int resourceCount,
            Set<UUID> rolesWithHandoffSignals
    ) {
        if (role.getRisk() == null || rolesWithHandoffSignals.contains(role.getId())) {
            return;
        }

        int incompleteItemCount = (int) activeItems.stream()
                .filter(item -> !item.isCompleted())
                .count();
        boolean handoffHasWarnings = RoleHandoff.hasWarnings(
                activeItems.size(),
                incompleteItemCount,
                resourceCount
        );
        if (!role.getResponsibilities().isEmpty() && !handoffHasWarnings) {
            return;
        }

        List<String> gaps = new ArrayList<>();
        if (role.getResponsibilities().isEmpty()) {
            gaps.add("책임 목록");
        }
        if (activeItems.isEmpty()) {
            gaps.add("활성 바통 항목");
        } else if (incompleteItemCount > 0) {
            gaps.add("미완료 바통 항목 " + incompleteItemCount + "개");
        }
        if (resourceCount == 0) {
            gaps.add("역할 자료");
        }

        signals.add(new ContinuitySignalResult(
                ContinuitySignalType.ROLE_PREPARATION_INCOMPLETE,
                ContinuitySignalSeverity.WARNING,
                role.getId(),
                null,
                role.getName() + " 준비 부족",
                role.getRisk() + " 다음 준비 요소가 부족합니다: "
                        + String.join(", ", gaps) + ".",
                "역할 화면과 바통북에서 빠진 책임, 항목과 자료를 보완하세요.",
                null
        ));
    }

    private void addRepeatedOverdueSignals(
            List<ContinuitySignalResult> signals,
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            Clock clock,
            ZoneId zoneId
    ) {
        Set<UUID> activeRoundIds = new HashSet<>();
        for (SeasonRound round : rounds) {
            if (round.getArchivedAt() == null) {
                activeRoundIds.add(round.getId());
            }
        }

        Map<UUID, Set<UUID>> overdueRoundIdsByRoutine = new HashMap<>();
        for (RoutineExecution execution : executions) {
            if (activeRoundIds.contains(execution.getSeasonRoundId())
                    && execution.timingStatus(clock, zoneId) == RoutineTimingStatus.OVERDUE) {
                overdueRoundIdsByRoutine
                        .computeIfAbsent(execution.getRoutineId(), ignored -> new HashSet<>())
                        .add(execution.getSeasonRoundId());
            }
        }

        for (Routine routine : routines) {
            if (routine.getArchivedAt() != null) {
                continue;
            }
            int overdueRoundCount = overdueRoundIdsByRoutine
                    .getOrDefault(routine.getId(), Set.of())
                    .size();
            if (overdueRoundCount < REPEATED_OVERDUE_ROUNDS) {
                continue;
            }
            signals.add(new ContinuitySignalResult(
                    ContinuitySignalType.ROUTINE_REPEATEDLY_OVERDUE,
                    overdueRoundCount >= 3
                            ? ContinuitySignalSeverity.CRITICAL
                            : ContinuitySignalSeverity.WARNING,
                    routine.getOwnerRoleId(),
                    routine.getId(),
                    routine.getTitle() + " 반복 지연",
                    routine.getTitle() + " 루틴이 서로 다른 " + overdueRoundCount
                            + "개 회차에서 마감 뒤에도 완료되지 않았습니다.",
                    "루틴의 담당, 마감과 실행 방법을 다시 정하고 밀린 회차를 정리하세요.",
                    null
            ));
        }
    }

    private Set<UUID> addHandoffSignals(
            List<ContinuitySignalResult> signals,
            List<Role> roles,
            Map<UUID, List<HandoffItem>> activeItemsByRole,
            List<RoleHandoff> roleHandoffs,
            Map<UUID, Member> membersById,
            Set<UUID> activeMemberIds,
            LocalDate today
    ) {
        Map<UUID, Role> rolesById = new HashMap<>();
        for (Role role : roles) {
            rolesById.put(role.getId(), role);
        }
        Set<UUID> openHandoffRoleIds = new HashSet<>();
        Set<UUID> signaledRoleIds = new HashSet<>();

        for (RoleHandoff handoff : roleHandoffs) {
            LocalDate incomingStartDate = handoff.getIncomingAssignmentStartDate();
            if (!handoff.isOpen()) {
                continue;
            }
            openHandoffRoleIds.add(handoff.getRoleId());

            HandoffReadiness readiness = handoffReadiness(
                    handoff,
                    activeItemsByRole.getOrDefault(handoff.getRoleId(), List.of())
            );
            boolean participantsActive = activeMemberIds.contains(handoff.getToMemberId())
                    && (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                            || activeMemberIds.contains(handoff.getFromMemberId()));
            boolean currentCoverageMissing =
                    handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                            && !activeMemberIds.contains(handoff.getFromMemberId());
            boolean coverageGap = hasNearCoverageGap(handoff, today);
            if (participantsActive
                    && !currentCoverageMissing
                    && !coverageGap
                    && incomingStartDate.isAfter(today.plusDays(HANDOFF_WARNING_DAYS))) {
                continue;
            }

            Role role = rolesById.get(handoff.getRoleId());
            if (role == null) {
                throw new IllegalStateException("역할 바통의 역할을 찾을 수 없습니다");
            }
            LocalDate relevantDate = handoffRelevantDate(handoff, coverageGap);
            String reason = participantsActive
                    ? handoffReason(
                    role,
                    handoff,
                    incomingStartDate,
                    readiness,
                    coverageGap,
                    currentCoverageMissing,
                    membersById
            )
                    : handoffParticipantReason(
                    handoff,
                    membersById,
                    currentCoverageMissing
            );
            String recommendedAction = participantsActive
                    ? handoffAction(
                    handoff,
                    readiness,
                    coverageGap,
                    currentCoverageMissing
            )
                    : "활동 종료한 구성원을 다시 활성화하거나 바통을 취소한 뒤 참여자를 다시 정하세요.";
            signals.add(new ContinuitySignalResult(
                    ContinuitySignalType.HANDOFF_INCOMPLETE,
                    participantsActive
                            && !currentCoverageMissing
                            && relevantDate.isAfter(today)
                            ? ContinuitySignalSeverity.WARNING
                            : ContinuitySignalSeverity.CRITICAL,
                    role.getId(),
                    null,
                    handoffTitle(
                            role,
                            handoff,
                            participantsActive,
                            readiness,
                            coverageGap,
                            currentCoverageMissing
                    ),
                    reason,
                    recommendedAction,
                    relevantDate
            ));
            signaledRoleIds.add(role.getId());
        }

        for (Role role : roles) {
            LocalDate assignmentEndDate = role.getAssignmentEndDate();
            if (openHandoffRoleIds.contains(role.getId())
                    || !activeMemberIds.contains(role.getCurrentMemberId())
                    || !hasEligibleNextMember(role, activeMemberIds)
                    || assignmentEndDate == null
                    || assignmentEndDate.isAfter(today.plusDays(SUCCESSOR_WARNING_DAYS))) {
                continue;
            }
            signals.add(new ContinuitySignalResult(
                    ContinuitySignalType.HANDOFF_INCOMPLETE,
                    assignmentEndDate.isAfter(today)
                            ? ContinuitySignalSeverity.WARNING
                            : ContinuitySignalSeverity.CRITICAL,
                    role.getId(),
                    null,
                    role.getName() + " 바통 준비 미시작",
                    role.getName() + " 역할의 다음 담당자는 정했지만 담당 종료가 "
                            + assignmentDateDescription(assignmentEndDate, today)
                            + " 실제 역할 바통을 시작하지 않았습니다.",
                    "바통 화면에서 다음 담당 기간을 확인하고 역할 바통을 준비하세요.",
                    assignmentEndDate
            ));
            signaledRoleIds.add(role.getId());
        }
        return signaledRoleIds;
    }

    private HandoffReadiness handoffReadiness(
            RoleHandoff handoff,
            List<HandoffItem> activeItems
    ) {
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return new HandoffReadiness(
                    Objects.requireNonNull(
                            handoff.getSnapshotItemCount(),
                            "전달된 바통의 항목 수 snapshot은 필수입니다"
                    ),
                    Objects.requireNonNull(
                            handoff.getSnapshotIncompleteItemCount(),
                            "전달된 바통의 미완료 수 snapshot은 필수입니다"
                    )
            );
        }
        return new HandoffReadiness(
                activeItems.size(),
                (int) activeItems.stream().filter(item -> !item.isCompleted()).count()
        );
    }

    private String currentMemberGapReason(Role role, Member currentMember) {
        if (role.getCurrentMemberId() == null) {
            return role.getName() + " 역할에 현재 담당자가 없습니다.";
        }
        if (currentMember == null) {
            return role.getName() + " 역할의 현재 담당자 기록을 확인할 수 없습니다.";
        }
        return role.getName() + " 역할의 현재 담당자 "
                + currentMember.getName() + "님이 활동을 종료했습니다.";
    }

    private String successorReason(
            Role role,
            Member nextMember,
            LocalDate assignmentEndDate,
            LocalDate today
    ) {
        String gapReason = nextMember == null
                ? "다음 담당자가 없습니다."
                : Objects.equals(role.getCurrentMemberId(), role.getNextMemberId())
                        ? "현재 담당자와 같은 " + nextMember.getName()
                        + "님을 다음 담당자로 지정해 역할을 넘길 수 없습니다."
                        : "다음 담당자로 정한 " + nextMember.getName()
                        + "님이 활동을 종료했습니다.";
        if (assignmentEndDate.isBefore(today)) {
            long elapsedDays = ChronoUnit.DAYS.between(assignmentEndDate, today);
            return role.getName() + " 역할의 담당 기간이 " + elapsedDays
                    + "일 전에 끝났지만 " + gapReason;
        }
        if (assignmentEndDate.equals(today)) {
            return role.getName() + " 역할의 담당 기간이 오늘 끝나지만 " + gapReason;
        }
        long remainingDays = ChronoUnit.DAYS.between(today, assignmentEndDate);
        return role.getName() + " 역할의 담당 기간이 " + remainingDays
                + "일 뒤 끝나지만 " + gapReason;
    }

    private String handoffTitle(
            Role role,
            RoleHandoff handoff,
            boolean participantsActive,
            HandoffReadiness readiness,
            boolean coverageGap,
            boolean currentCoverageMissing
    ) {
        if (!participantsActive) {
            return role.getName() + " 바통 참여자 확인 필요";
        }
        if (coverageGap) {
            return role.getName() + " 담당 공백 예정";
        }
        if (currentCoverageMissing) {
            return role.getName() + " 현재 담당 공백·바통 수락 대기";
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return role.getName() + " 바통 수락 대기";
        }
        if (readiness.itemCount() > 0 && readiness.incompleteItemCount() == 0) {
            return role.getName() + " 바통 전달 대기";
        }
        return role.getName() + " 바통 준비 지연";
    }

    private String handoffReason(
            Role role,
            RoleHandoff handoff,
            LocalDate incomingStartDate,
            HandoffReadiness readiness,
            boolean coverageGap,
            boolean currentCoverageMissing,
            Map<UUID, Member> membersById
    ) {
        if (coverageGap) {
            LocalDate outgoingEndDate = handoff.getOutgoingAssignmentEndDate();
            long uncoveredDays = ChronoUnit.DAYS.between(
                    outgoingEndDate,
                    incomingStartDate
            ) - 1;
            return role.getName() + " 역할의 현재 담당은 " + outgoingEndDate
                    + "에 끝나지만 새 담당은 " + incomingStartDate
                    + "에 시작해 " + uncoveredDays + "일의 담당 공백이 생깁니다.";
        }
        if (currentCoverageMissing) {
            Member fromMember = membersById.get(handoff.getFromMemberId());
            String formerOwner = fromMember == null
                    ? "이전 담당자 기록을 확인할 수 없어"
                    : "이전 담당자 " + fromMember.getName() + "님이 활동을 종료해";
            return formerOwner + " 현재 담당 공백입니다. 전달된 바통은 다음 담당자가 "
                    + "즉시 수락할 수 있습니다.";
        }
        String dateReason = role.getName() + " 역할의 새 담당 시작일이 "
                + incomingStartDate + "입니다. ";
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            if (readiness.itemCount() == 0) {
                return dateReason + "전달 snapshot에 바통 항목이 없고 아직 수락하지 않았습니다.";
            }
            if (readiness.incompleteItemCount() > 0) {
                return dateReason + "전달 snapshot에 미완료 바통 항목이 "
                        + readiness.incompleteItemCount() + "개 있고 아직 수락하지 않았습니다.";
            }
            return dateReason + "바통 전달은 끝났지만 아직 다음 담당자가 수락하지 않았습니다.";
        }
        if (readiness.itemCount() == 0) {
            return dateReason + "준비한 활성 바통 항목이 없습니다.";
        }
        if (readiness.incompleteItemCount() > 0) {
            return dateReason + "미완료 바통 항목이 "
                    + readiness.incompleteItemCount() + "개 남아 있습니다.";
        }
        return dateReason + "바통 항목 준비는 끝났지만 아직 전달하지 않았습니다.";
    }

    private String handoffAction(
            RoleHandoff handoff,
            HandoffReadiness readiness,
            boolean coverageGap,
            boolean currentCoverageMissing
    ) {
        if (coverageGap) {
            return "현재 담당자가 바통을 취소하고 담당 기간이 이어지도록 다시 준비하세요.";
        }
        if (currentCoverageMissing) {
            return "다음 담당자가 바통을 즉시 수락하거나 현재 담당자 명의로 바통을 취소하세요.";
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return "다음 담당자가 바통을 수락하고 남은 항목을 확인하세요.";
        }
        if (readiness.itemCount() == 0) {
            return "활성 바통 항목을 추가한 뒤 현재 담당자가 바통을 전달하세요.";
        }
        if (readiness.incompleteItemCount() == 0) {
            return "현재 담당자가 준비된 바통을 다음 담당자에게 전달하세요.";
        }
        return "미완료 항목을 정리한 뒤 현재 담당자가 바통을 전달하세요.";
    }

    private String handoffParticipantReason(
            RoleHandoff handoff,
            Map<UUID, Member> membersById,
            boolean currentCoverageMissing
    ) {
        List<String> unavailableParticipants = new ArrayList<>();
        if (handoff.getStatus() == RoleHandoffStatus.PREPARING) {
            addUnavailableParticipant(
                    unavailableParticipants,
                    "현재 담당자",
                    handoff.getFromMemberId(),
                    membersById
            );
        }
        addUnavailableParticipant(
                unavailableParticipants,
                "다음 담당자",
                handoff.getToMemberId(),
                membersById
        );
        String currentGap = currentCoverageMissing
                ? " 이전 담당자의 활동 종료로 현재 담당도 비어 있습니다."
                : "";
        return "열린 역할 바통에서 " + String.join(" 및 ", unavailableParticipants)
                + " 상태를 확인해야 전달이나 수락을 계속할 수 있습니다." + currentGap;
    }

    private void addUnavailableParticipant(
            List<String> unavailableParticipants,
            String roleLabel,
            UUID memberId,
            Map<UUID, Member> membersById
    ) {
        Member member = membersById.get(memberId);
        if (member == null) {
            unavailableParticipants.add(roleLabel + " 기록 누락");
            return;
        }
        if (!member.isActive()) {
            unavailableParticipants.add(roleLabel + " " + member.getName() + "님의 활동 종료");
        }
    }

    private String assignmentDateDescription(LocalDate assignmentEndDate, LocalDate today) {
        if (assignmentEndDate.isBefore(today)) {
            return ChronoUnit.DAYS.between(assignmentEndDate, today) + "일 전에 끝났지만";
        }
        if (assignmentEndDate.equals(today)) {
            return "오늘 끝나지만";
        }
        return ChronoUnit.DAYS.between(today, assignmentEndDate) + "일 뒤 끝나지만";
    }

    private boolean hasEligibleNextMember(Role role, Set<UUID> activeMemberIds) {
        return activeMemberIds.contains(role.getNextMemberId())
                && !Objects.equals(role.getCurrentMemberId(), role.getNextMemberId());
    }

    private boolean hasNearCoverageGap(RoleHandoff handoff, LocalDate today) {
        LocalDate outgoingEndDate = handoff.getOutgoingAssignmentEndDate();
        return outgoingEndDate != null
                && !outgoingEndDate.isAfter(today.plusDays(SUCCESSOR_WARNING_DAYS))
                && handoff.getIncomingAssignmentStartDate().isAfter(
                outgoingEndDate.plusDays(1)
        );
    }

    private LocalDate handoffRelevantDate(RoleHandoff handoff, boolean coverageGap) {
        return coverageGap
                ? handoff.getOutgoingAssignmentEndDate()
                : handoff.getIncomingAssignmentStartDate();
    }

    private Map<UUID, List<HandoffItem>> activeItemsByRole(List<HandoffItem> handoffItems) {
        Map<UUID, List<HandoffItem>> activeItemsByRole = new HashMap<>();
        for (HandoffItem item : handoffItems) {
            if (item.getArchivedAt() == null) {
                activeItemsByRole
                        .computeIfAbsent(item.getRoleId(), ignored -> new ArrayList<>())
                        .add(item);
            }
        }
        return activeItemsByRole;
    }

    private Map<UUID, Integer> countsByRole(List<RoleResource> resources) {
        Map<UUID, Integer> countsByRole = new HashMap<>();
        for (RoleResource resource : resources) {
            if (resource.getArchivedAt() == null) {
                countsByRole.merge(resource.getRoleId(), 1, Integer::sum);
            }
        }
        return countsByRole;
    }

    private Map<UUID, Member> membersById(List<Member> members) {
        Map<UUID, Member> membersById = new HashMap<>();
        for (Member member : members) {
            membersById.put(member.getId(), member);
        }
        return membersById;
    }

    private Set<UUID> activeMemberIds(List<Member> members) {
        Set<UUID> activeMemberIds = new HashSet<>();
        for (Member member : members) {
            if (member.isActive()) {
                activeMemberIds.add(member.getId());
            }
        }
        return activeMemberIds;
    }

    private Comparator<ContinuitySignalResult> continuitySignalOrder() {
        return Comparator
                .comparingInt((ContinuitySignalResult signal) ->
                        signal.severity() == ContinuitySignalSeverity.CRITICAL ? 0 : 1)
                .thenComparing(
                        ContinuitySignalResult::relevantDate,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparingInt(signal -> typePriority(signal.type()))
                .thenComparing(ContinuitySignalResult::title)
                .thenComparing(ContinuitySignalResult::roleId)
                .thenComparing(
                        ContinuitySignalResult::routineId,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
    }

    private int typePriority(ContinuitySignalType type) {
        return switch (type) {
            case ROLE_UNASSIGNED -> 0;
            case ROLE_SUCCESSOR_MISSING -> 1;
            case HANDOFF_INCOMPLETE -> 2;
            case ROUTINE_REPEATEDLY_OVERDUE -> 3;
            case ROLE_PREPARATION_INCOMPLETE -> 4;
        };
    }

    private record HandoffReadiness(int itemCount, int incompleteItemCount) {
    }
}
