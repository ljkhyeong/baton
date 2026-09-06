package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.ContinuitySignalResult;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class HandoffContinuitySignalAnalyzer {

    private static final int SUCCESSOR_WARNING_DAYS = 14;
    private static final int HANDOFF_WARNING_DAYS = 7;

    Analysis analyze(
            List<Role> roles,
            List<HandoffItem> handoffItems,
            List<RoleHandoff> roleHandoffs,
            List<Member> members,
            LocalDate today
    ) {
        Map<UUID, Role> rolesById = new HashMap<>();
        for (Role role : roles) {
            rolesById.put(role.getId(), role);
        }
        Map<UUID, List<HandoffItem>> activeItemsByRole = activeItemsByRole(handoffItems);
        Map<UUID, Member> membersById = membersById(members);
        Set<UUID> activeMemberIds = activeMemberIds(members);
        Set<UUID> openHandoffRoleIds = new HashSet<>();
        Set<UUID> signaledRoleIds = new HashSet<>();
        List<ContinuitySignalResult> signals = new ArrayList<>();

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
            boolean currentCoverageMissing = handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
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
                throw new IllegalStateException("역할 인수인계의 역할을 찾을 수 없습니다");
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
                    : handoffParticipantReason(handoff, membersById, currentCoverageMissing);
            String recommendedAction = participantsActive
                    ? handoffAction(handoff, readiness, coverageGap, currentCoverageMissing)
                    : "활동 종료한 구성원을 다시 활성화하거나 인수인계를 취소한 뒤 참여자를 다시 정하세요.";
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
                    role.getName() + " 인수인계를 시작해 주세요",
                    role.getName() + " 역할의 다음 담당자는 정했습니다. 현재 담당 기간이 "
                            + assignmentDateDescription(assignmentEndDate, today)
                            + " 인수인계를 시작하지 않았습니다.",
                    "인수인계 화면에서 다음 담당 기간을 확인하고 역할 인수인계를 준비하세요.",
                    assignmentEndDate
            ));
            signaledRoleIds.add(role.getId());
        }
        return new Analysis(signals, signaledRoleIds);
    }

    private HandoffReadiness handoffReadiness(
            RoleHandoff handoff,
            List<HandoffItem> activeItems
    ) {
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return new HandoffReadiness(
                    Objects.requireNonNull(
                            handoff.getSnapshotItemCount(),
                            "전달된 인수인계의 항목 수 snapshot은 필수입니다"
                    ),
                    Objects.requireNonNull(
                            handoff.getSnapshotIncompleteItemCount(),
                            "전달된 인수인계의 미완료 수 snapshot은 필수입니다"
                    )
            );
        }
        return new HandoffReadiness(
                activeItems.size(),
                (int) activeItems.stream().filter(item -> !item.isCompleted()).count()
        );
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
            return role.getName() + " 인수인계 참여자 확인 필요";
        }
        if (coverageGap) {
            return role.getName() + " 담당자 없는 기간 예정";
        }
        if (currentCoverageMissing) {
            return role.getName() + " 담당자 없음 · 인수인계 수락 필요";
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return role.getName() + " 인수인계 수락 대기";
        }
        if (readiness.itemCount() > 0 && readiness.incompleteItemCount() == 0) {
            return role.getName() + " 인수인계 전달 대기";
        }
        return role.getName() + " 인수인계 준비 지연";
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
            long uncoveredDays = ChronoUnit.DAYS.between(outgoingEndDate, incomingStartDate) - 1;
            return role.getName() + " 역할의 현재 담당은 " + outgoingEndDate
                    + "에 끝나지만 새 담당은 " + incomingStartDate
                    + "에 시작해 " + uncoveredDays + "일의 담당 공백이 생깁니다.";
        }
        if (currentCoverageMissing) {
            Member fromMember = membersById.get(handoff.getFromMemberId());
            String formerOwner = fromMember == null
                    ? "이전 담당자 기록을 확인할 수 없어"
                    : "이전 담당자 " + fromMember.getName() + "님이 활동을 종료해";
            return formerOwner + " 현재 담당자가 없습니다. 전달된 인수인계는 다음 담당자가 "
                    + "즉시 수락할 수 있습니다.";
        }
        String dateReason = role.getName() + " 역할의 새 담당 시작일이 " + incomingStartDate + "입니다. ";
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            if (readiness.itemCount() == 0) {
                return dateReason + "전달 당시 기록에 인수인계 항목이 없고 아직 수락하지 않았습니다.";
            }
            if (readiness.incompleteItemCount() > 0) {
                return dateReason + "전달 당시 기록에 미완료 인수인계 항목이 "
                        + readiness.incompleteItemCount() + "개 있고 아직 수락하지 않았습니다.";
            }
            return dateReason + "인수인계 전달은 끝났지만 아직 다음 담당자가 수락하지 않았습니다.";
        }
        if (readiness.itemCount() == 0) {
            return dateReason + "등록된 인수인계 항목이 없습니다.";
        }
        if (readiness.incompleteItemCount() > 0) {
            return dateReason + "미완료 인수인계 항목이 "
                    + readiness.incompleteItemCount() + "개 남아 있습니다.";
        }
        return dateReason + "인수인계 항목 준비는 끝났지만 아직 전달하지 않았습니다.";
    }

    private String handoffAction(
            RoleHandoff handoff,
            HandoffReadiness readiness,
            boolean coverageGap,
            boolean currentCoverageMissing
    ) {
        if (coverageGap) {
            return "현재 담당자가 인수인계를 취소하고 담당 기간이 이어지도록 다시 준비하세요.";
        }
        if (currentCoverageMissing) {
            return "다음 담당자가 인수인계를 즉시 수락하거나 현재 담당자 명의로 인수인계를 취소하세요.";
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
            return "다음 담당자가 인수인계를 수락하고 남은 항목을 확인하세요.";
        }
        if (readiness.itemCount() == 0) {
            return "인수인계 항목을 추가한 뒤 현재 담당자가 다음 담당자에게 전달하세요.";
        }
        if (readiness.incompleteItemCount() == 0) {
            return "현재 담당자가 준비된 인수인계를 다음 담당자에게 전달하세요.";
        }
        return "미완료 항목을 정리한 뒤 현재 담당자가 인수인계를 전달하세요.";
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
        return "진행 중인 역할 인수인계에서 " + String.join(" 및 ", unavailableParticipants)
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
                && handoff.getIncomingAssignmentStartDate().isAfter(outgoingEndDate.plusDays(1));
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

    record Analysis(List<ContinuitySignalResult> signals, Set<UUID> signaledRoleIds) {
    }

    private record HandoffReadiness(int itemCount, int incompleteItemCount) {
    }
}
