package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import java.time.Clock;

final class WorkspaceServiceTestFactory {

    private WorkspaceServiceTestFactory() {
    }

    static Services create(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceSecrets workspaceSecrets,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        ContinuitySignalAnalyzer analyzer = new ContinuitySignalAnalyzer();
        WorkspaceResultMapper resultMapper = new WorkspaceResultMapper(clock);
        WorkspaceProjectionReader projectionReader = new WorkspaceProjectionReader(
                repository,
                clock,
                new WorkspaceContinuitySnapshotReader(repository, repository, repository),
                analyzer,
                resultMapper
        );
        WorkspaceAccessControl accessControl = new WorkspaceAccessControl(workspaceSecrets);
        WorkspaceCreationCoordinator creationCoordinator = new WorkspaceCreationCoordinator(
                repository,
                accessControl,
                calendarChangeRecorder
        );
        WorkspaceScopeAuthorizer scopeAuthorizer = new WorkspaceScopeAuthorizer(
                repository,
                accessControl
        );
        WorkspaceAccessKeyCoordinator accessKeyCoordinator = new WorkspaceAccessKeyCoordinator(
                repository,
                scopeAuthorizer,
                accessControl
        );
        WorkspaceContentIdempotency contentIdempotency = new WorkspaceContentIdempotency(repository);
        WorkspaceMemberResolver memberResolver = new WorkspaceMemberResolver(repository);
        WorkspaceMemberCoordinator memberCoordinator = new WorkspaceMemberCoordinator(
                repository,
                clock,
                contentIdempotency,
                memberResolver,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoleResolver roleResolver = new WorkspaceRoleResolver(repository);
        WorkspaceRolePolicy rolePolicy = new WorkspaceRolePolicy(repository);
        WorkspaceRoleCoordinator roleCoordinator = new WorkspaceRoleCoordinator(
                repository,
                contentIdempotency,
                memberResolver,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoleHandoffCoordinator roleHandoffCoordinator = new WorkspaceRoleHandoffCoordinator(
                repository,
                clock,
                contentIdempotency,
                memberResolver,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoundSchedulePolicy roundSchedulePolicy = new WorkspaceRoundSchedulePolicy(repository);
        WorkspaceRoutineCoordinator routineCoordinator = new WorkspaceRoutineCoordinator(
                repository,
                clock,
                contentIdempotency,
                roleResolver,
                resultMapper,
                roundSchedulePolicy,
                briefContinuitySignalRecorder
        );
        WorkspaceRoundCoordinator roundCoordinator = new WorkspaceRoundCoordinator(
                repository,
                clock,
                contentIdempotency,
                resultMapper,
                new WorkspaceSeasonRoundResolver(repository),
                new RoutineExecutionSnapshotFactory(),
                calendarChangeRecorder,
                briefContinuitySignalRecorder
        );
        WorkspaceDecisionCoordinator decisionCoordinator = new WorkspaceDecisionCoordinator(
                repository,
                clock,
                contentIdempotency,
                memberResolver,
                resultMapper
        );
        WorkspaceHandoffItemCoordinator handoffItemCoordinator = new WorkspaceHandoffItemCoordinator(
                repository,
                clock,
                contentIdempotency,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoleResourceCoordinator roleResourceCoordinator = new WorkspaceRoleResourceCoordinator(
                repository,
                clock,
                contentIdempotency,
                roleResolver,
                rolePolicy,
                resultMapper,
                watchMonitorChangeRecorder,
                briefContinuitySignalRecorder
        );
        WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator = new WorkspaceSeasonSettingsCoordinator(
                repository,
                resultMapper,
                roundSchedulePolicy,
                calendarChangeRecorder
        );
        WorkspaceSeasonLifecycleCoordinator seasonLifecycleCoordinator = new WorkspaceSeasonLifecycleCoordinator(
                repository,
                clock,
                contentIdempotency,
                resultMapper,
                watchMonitorChangeRecorder,
                calendarChangeRecorder,
                briefContinuitySignalRecorder
        );
        return new Services(
                new WorkspaceAccessService(scopeAuthorizer),
                new WorkspaceLifecycleService(
                        projectionReader,
                        accessControl,
                        creationCoordinator,
                        scopeAuthorizer,
                        accessKeyCoordinator,
                        seasonSettingsCoordinator,
                        seasonLifecycleCoordinator,
                        briefContinuitySignalRecorder
                ),
                new WorkspacePeopleService(
                        scopeAuthorizer,
                        memberCoordinator,
                        roleCoordinator,
                        roleHandoffCoordinator,
                        briefContinuitySignalRecorder
                ),
                new WorkspaceOperationsService(
                        scopeAuthorizer,
                        routineCoordinator,
                        roundCoordinator
                ),
                new WorkspaceRecordsService(
                        scopeAuthorizer,
                        decisionCoordinator,
                        handoffItemCoordinator,
                        roleResourceCoordinator
                )
        );
    }

    record Services(
            WorkspaceAccessService access,
            WorkspaceLifecycleService lifecycle,
            WorkspacePeopleService people,
            WorkspaceOperationsService operations,
            WorkspaceRecordsService records
    ) {
    }
}
