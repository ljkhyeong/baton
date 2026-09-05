package com.personal.baton.application.workspace;

import static org.mockito.Mockito.mock;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import java.time.Clock;

final class WorkspaceServiceTestFactory {

    private WorkspaceServiceTestFactory() {
    }

    static Services create(
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceOperationsRepository operationsRepository,
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceSecrets workspaceSecrets,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        ContinuitySignalAnalyzer analyzer = new ContinuitySignalAnalyzer();
        WorkspaceResultMapper resultMapper = new WorkspaceResultMapper(clock);
        WorkspaceProjectionReader projectionReader = new WorkspaceProjectionReader(
                seasonRepository,
                recordsRepository,
                clock,
                new WorkspaceContinuitySnapshotReader(
                        peopleRepository,
                        operationsRepository,
                        recordsRepository
                ),
                analyzer,
                resultMapper
        );
        WorkspaceAccessControl accessControl = new WorkspaceAccessControl(workspaceSecrets);
        WorkspaceCreationCoordinator creationCoordinator = new WorkspaceCreationCoordinator(
                accessRepository,
                seasonRepository,
                peopleRepository,
                accessControl,
                calendarChangeRecorder
        );
        WorkspaceScopeAuthorizer scopeAuthorizer = new WorkspaceScopeAuthorizer(
                accessRepository,
                seasonRepository,
                accessControl,
                mock(TeamAccountAccessPolicy.class)
        );
        WorkspaceAccessKeyCoordinator accessKeyCoordinator = new WorkspaceAccessKeyCoordinator(
                accessRepository,
                seasonRepository,
                scopeAuthorizer,
                accessControl
        );
        WorkspaceContentIdempotency contentIdempotency = new WorkspaceContentIdempotency(accessRepository);
        WorkspaceMemberResolver memberResolver = new WorkspaceMemberResolver(peopleRepository);
        WorkspaceMemberCoordinator memberCoordinator = new WorkspaceMemberCoordinator(
                peopleRepository,
                clock,
                contentIdempotency,
                memberResolver,
                resultMapper,
                briefContinuitySignalRecorder,
                org.mockito.Mockito.mock(com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore.class)
        );
        WorkspaceRoleResolver roleResolver = new WorkspaceRoleResolver(peopleRepository);
        WorkspaceRolePolicy rolePolicy = new WorkspaceRolePolicy(peopleRepository);
        WorkspaceRoleCoordinator roleCoordinator = new WorkspaceRoleCoordinator(
                peopleRepository,
                contentIdempotency,
                memberResolver,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoleHandoffCoordinator roleHandoffCoordinator = new WorkspaceRoleHandoffCoordinator(
                peopleRepository,
                recordsRepository,
                clock,
                contentIdempotency,
                memberResolver,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoundSchedulePolicy roundSchedulePolicy = new WorkspaceRoundSchedulePolicy(operationsRepository);
        WorkspaceRoutineCoordinator routineCoordinator = new WorkspaceRoutineCoordinator(
                operationsRepository,
                clock,
                contentIdempotency,
                roleResolver,
                resultMapper,
                roundSchedulePolicy,
                briefContinuitySignalRecorder
        );
        WorkspaceRoundCoordinator roundCoordinator = new WorkspaceRoundCoordinator(
                operationsRepository,
                clock,
                contentIdempotency,
                resultMapper,
                new WorkspaceSeasonRoundResolver(operationsRepository),
                new RoutineExecutionSnapshotFactory(),
                calendarChangeRecorder,
                briefContinuitySignalRecorder
        );
        WorkspaceDecisionCoordinator decisionCoordinator = new WorkspaceDecisionCoordinator(
                recordsRepository,
                peopleRepository,
                clock,
                contentIdempotency,
                memberResolver,
                resultMapper,
                mock(ContentChangeRecorder.class)
        );
        WorkspaceHandoffItemCoordinator handoffItemCoordinator = new WorkspaceHandoffItemCoordinator(
                recordsRepository,
                clock,
                contentIdempotency,
                roleResolver,
                rolePolicy,
                resultMapper,
                briefContinuitySignalRecorder
        );
        WorkspaceRoleResourceCoordinator roleResourceCoordinator = new WorkspaceRoleResourceCoordinator(
                recordsRepository,
                clock,
                contentIdempotency,
                roleResolver,
                rolePolicy,
                resultMapper,
                watchMonitorChangeRecorder,
                briefContinuitySignalRecorder,
                mock(ContentChangeRecorder.class)
        );
        WorkspaceSeasonSettingsCoordinator seasonSettingsCoordinator = new WorkspaceSeasonSettingsCoordinator(
                seasonRepository,
                operationsRepository,
                peopleRepository,
                resultMapper,
                roundSchedulePolicy,
                calendarChangeRecorder
        );
        WorkspaceSeasonEndingCoordinator seasonEndingCoordinator =
                new WorkspaceSeasonEndingCoordinator(
                        seasonRepository,
                        peopleRepository,
                        recordsRepository,
                        clock,
                        resultMapper,
                        watchMonitorChangeRecorder
                );
        WorkspaceSeasonSuccessorCoordinator seasonSuccessorCoordinator =
                new WorkspaceSeasonSuccessorCoordinator(
                        seasonRepository,
                        peopleRepository,
                        operationsRepository,
                        recordsRepository,
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
                        seasonEndingCoordinator,
                        seasonSuccessorCoordinator,
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
