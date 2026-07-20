export type ViewKey = 'today' | 'roles' | 'rhythm' | 'memory' | 'handoff'

export type Team = {
  id: string
  name: string
}

export type Season = {
  id: string
  name: string
  startDate: string
  endDate: string
}

export type Member = {
  id: string
  name: string
  initials: string
  tone: string
}

export type Role = {
  id: string
  name: string
  purpose: string
  currentMemberId: string | null
  nextMemberId: string | null
  assignmentStartDate: string | null
  assignmentEndDate: string | null
  responsibilities: string[]
  risk: string | null
}

export type RoutinePhase = 'BEFORE' | 'DURING' | 'AFTER'
export type RoutineStatus = 'WAITING' | 'DONE'

export type Routine = {
  id: string
  title: string
  phase: RoutinePhase
  dueLabel: string
  ownerRoleId: string
  status: RoutineStatus
  detail: string
}

export type Decision = {
  id: string
  title: string
  reason: string
  alternative: string
  createdAt: string
  authorName: string
  roleIds: string[]
}

export type HandoffCategory = 'RESPONSIBILITY' | 'ROUTINE' | 'RESOURCE' | 'ADVICE'

export type HandoffItem = {
  id: string
  roleId: string
  label: string
  category: HandoffCategory
  completed: boolean
}

export type WorkspaceProjection = {
  team: Team
  season: Season
  members: Member[]
  roles: Role[]
  routines: Routine[]
  decisions: Decision[]
  handoffItems: HandoffItem[]
}

export type CreateWorkspaceRequest = {
  teamName: string
  seasonName: string
  startDate: string
  endDate: string
  memberNames: string[]
}

export type CreateWorkspaceResponse = {
  teamId: string
  seasonId: string
  accessKey: string
}

export type CreateRoleRequest = {
  name: string
  purpose: string
  currentMemberId: string | null
  nextMemberId: string | null
  assignmentStartDate: string | null
  assignmentEndDate: string | null
  responsibilities: string[]
  risk: string | null
}

export type CreateRoutineRequest = {
  title: string
  phase: RoutinePhase
  dueLabel: string
  ownerRoleId: string
  detail: string
}

export type CreateDecisionRequest = {
  title: string
  reason: string
  alternative: string
  authorMemberId: string
  roleIds: string[]
}

export type CreateHandoffItemRequest = {
  roleId: string
  label: string
  category: HandoffCategory
}
