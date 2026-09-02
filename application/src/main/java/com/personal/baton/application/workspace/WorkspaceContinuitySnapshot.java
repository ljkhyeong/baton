package com.personal.baton.application.workspace;

import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.SeasonRound;
import java.util.List;

record WorkspaceContinuitySnapshot(
        List<Member> members,
        List<Role> roles,
        List<Routine> routines,
        List<SeasonRound> rounds,
        List<RoutineExecution> executions,
        List<HandoffItem> handoffItems,
        List<RoleResource> resources,
        List<RoleHandoff> roleHandoffs
) {
}
