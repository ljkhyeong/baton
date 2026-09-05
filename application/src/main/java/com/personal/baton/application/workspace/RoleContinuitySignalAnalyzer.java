package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.ContinuitySignalResult;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
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

final class RoleContinuitySignalAnalyzer {

    private static final int SUCCESSOR_WARNING_DAYS = 14;

    List<ContinuitySignalResult> analyze(
            LocalDate seasonStartDate,
            List<Member> members,
            List<Role> roles,
            List<HandoffItem> handoffItems,
            List<RoleResource> resources,
            Set<UUID> rolesWithHandoffSignals,
            LocalDate today
    ) {
        Map<UUID, Member> membersById = membersById(members);
        Set<UUID> activeMemberIds = activeMemberIds(members);
        Map<UUID, List<HandoffItem>> activeItemsByRole = activeItemsByRole(handoffItems);
        Map<UUID, Integer> resourceCountsByRole = countsByRole(resources);
        List<ContinuitySignalResult> signals = new ArrayList<>();

        for (Role role : roles) {
            boolean coveredByHandoffSignal = rolesWithHandoffSignals.contains(role.getId());
            boolean currentMemberActive = activeMemberIds.contains(role.getCurrentMemberId());
            if (!currentMemberActive && !coveredByHandoffSignal) {
                signals.add(new ContinuitySignalResult(
                        ContinuitySignalType.ROLE_UNASSIGNED,
                        today.isBefore(seasonStartDate)
                                ? ContinuitySignalSeverity.WARNING
                                : ContinuitySignalSeverity.CRITICAL,
                        role.getId(),
                        null,
                        role.getName() + " 담당자 공백",
                        currentMemberGapReason(role, membersById.get(role.getCurrentMemberId())),
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
        return signals;
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
                role.getRisk() + " 다음 준비 요소가 부족합니다: " + String.join(", ", gaps) + ".",
                "역할 화면과 인수인계 문서에서 빠진 책임, 항목과 자료를 보완하세요.",
                null
        ));
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
            return role.getName() + " 역할의 담당 기간이 " + elapsedDays + "일 전에 끝났지만 " + gapReason;
        }
        if (assignmentEndDate.equals(today)) {
            return role.getName() + " 역할의 담당 기간이 오늘 끝나지만 " + gapReason;
        }
        long remainingDays = ChronoUnit.DAYS.between(today, assignmentEndDate);
        return role.getName() + " 역할의 담당 기간이 " + remainingDays + "일 뒤 끝나지만 " + gapReason;
    }

    private boolean hasEligibleNextMember(Role role, Set<UUID> activeMemberIds) {
        return activeMemberIds.contains(role.getNextMemberId())
                && !Objects.equals(role.getCurrentMemberId(), role.getNextMemberId());
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
}
