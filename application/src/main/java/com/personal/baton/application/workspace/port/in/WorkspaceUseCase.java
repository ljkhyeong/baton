package com.personal.baton.application.workspace.port.in;

/**
 * Compatibility umbrella for callers that still depend on the original workspace port.
 */
public interface WorkspaceUseCase extends
        WorkspaceCreationUseCase,
        WorkspaceQueryUseCase,
        WorkspaceAccessKeyUseCase,
        WorkspaceSeasonUseCase,
        WorkspaceMemberUseCase,
        WorkspaceRoleUseCase,
        WorkspaceRoleHandoffUseCase,
        WorkspaceRoutineUseCase,
        WorkspaceSeasonRoundUseCase,
        WorkspaceDecisionUseCase,
        WorkspaceHandoffItemUseCase,
        WorkspaceRoleResourceUseCase,
        WorkspaceRoleResourceQueryUseCase {
}
