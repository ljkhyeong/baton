package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.WorkspaceTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class WorkspaceTemplateInitializer {
    private final WorkspacePeopleRepository people;
    private final WorkspaceOperationsRepository operations;
    private final BriefContinuitySignalRecorder signals;

    WorkspaceTemplateInitializer(WorkspacePeopleRepository people, WorkspaceOperationsRepository operations,
            BriefContinuitySignalRecorder signals) {
        this.people = people;
        this.operations = operations;
        this.signals = signals;
    }

    void initialize(WorkspaceTemplate template, UUID teamId, UUID seasonId) {
        if (template == null) return;
        // 배포한 템플릿은 유지하고 구성을 바꿀 때 새 버전 식별자를 추가한다.
        var starters = switch (template) {
            case STUDY_V1 -> List.of(
                    new Starter("진행 담당", "모임의 목표와 진행 순서를 정리합니다.", "모임 안건 공유", RoutinePhase.BEFORE, "모임 전", "이번 모임의 목표와 진행 순서를 공유합니다."),
                    new Starter("학습 준비 담당", "함께 볼 학습 자료와 문제를 준비합니다.", "학습 자료 준비", RoutinePhase.BEFORE, "모임 전", "자료 접근 권한과 준비 범위를 확인합니다."),
                    new Starter("기록 담당", "학습 결과와 결정의 이유를 남깁니다.", "회고와 결정 정리", RoutinePhase.AFTER, "모임 후", "배운 내용과 다음 모임까지 할 일을 정리합니다."));
            case TEAM_V1 -> List.of(
                    new Starter("운영 담당", "팀의 운영 목표와 회의 안건을 관리합니다.", "회의 안건 정리", RoutinePhase.BEFORE, "회의 전", "결정이 필요한 안건과 참고 자료를 준비합니다."),
                    new Starter("일정 담당", "진행 상황과 일정의 변화를 확인합니다.", "진행 상태 확인", RoutinePhase.DURING, "회의 중", "지연된 일과 담당자 지원이 필요한 일을 확인합니다."),
                    new Starter("기록 담당", "결정과 후속 작업을 팀의 기록으로 남깁니다.", "결정과 후속 작업 정리", RoutinePhase.AFTER, "회의 후", "결정 이유와 다음 담당자의 행동을 기록합니다."));
        };
        for (var starter : starters) {
            var role = Role.create(UUID.randomUUID(), teamId, seasonId, starter.roleName(), starter.purpose(),
                    null, null, null, null, List.of(starter.detail()), null);
            people.saveRole(role);
            operations.saveRoutine(Routine.create(UUID.randomUUID(), seasonId, starter.routineTitle(), starter.phase(),
                    starter.dueLabel(), role.getId(), starter.detail(), null, null));
        }
        signals.reconcileSeason(teamId, seasonId);
    }

    private record Starter(String roleName, String purpose, String routineTitle, RoutinePhase phase,
            String dueLabel, String detail) {}
}
