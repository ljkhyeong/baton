import type { Dispatch, SetStateAction } from 'react'
import type { OpenWorkspaceModal } from './useWorkspaceUiState'
import { isActiveMember, isRoleHandoffLocked } from './workspacePresentation'
import type {
  Decision,
  HandoffItem,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  SeasonRound,
  ViewKey,
} from './types'

export type WorkspaceEditor =
  | { type: 'member'; value: Member }
  | { type: 'role'; value: Role }
  | { type: 'roleResource'; value: RoleResource }
  | { type: 'roleResourceCopy'; value: RoleResource }
  | { type: 'routine'; value: Routine }
  | { type: 'round'; value: SeasonRound }
  | { type: 'decision'; value: Decision }
  | { type: 'handoffItem'; value: HandoffItem }
  | null

type Resettable = { reset: () => void }

type WorkspaceEditorActionOptions = {
  roles: Role[]
  members: Member[]
  activeRoutines: Routine[]
  roleHandoffs: RoleHandoff[]
  selectedRole?: Role
  busyRoundIds: ReadonlySet<string>
  busyHandoffItemIds: ReadonlySet<string>
  conflictRecoveryActive: boolean
  ensureFreshWorkspace: () => boolean
  setEditor: Dispatch<SetStateAction<WorkspaceEditor>>
  setView: Dispatch<SetStateAction<ViewKey>>
  setSelectedRoleId: Dispatch<SetStateAction<string>>
  openModal: (modal: OpenWorkspaceModal) => void
  notify: (message: string, tone?: 'success' | 'error') => void
  commands: {
    memberCreation: Resettable
    roleCreation: Resettable
    roleResourceCreation: Resettable
    routineCreation: Resettable
    roundCreation: Resettable
    decisionCreation: Resettable
    handoffItemCreation: Resettable
  }
  mutations: {
    memberUpdate: Resettable
    memberDeactivation: Resettable
    roleUpdate: Resettable
    roleResourceUpdate: Resettable
    routineUpdate: Resettable
    roundUpdate: Resettable
    decisionUpdate: Resettable
    handoffItemUpdate: Resettable
  }
}

export function createWorkspaceEditorActions({
  roles,
  members,
  activeRoutines,
  roleHandoffs,
  selectedRole,
  busyRoundIds,
  busyHandoffItemIds,
  conflictRecoveryActive,
  ensureFreshWorkspace,
  setEditor,
  setView,
  setSelectedRoleId,
  openModal,
  notify,
  commands,
  mutations,
}: WorkspaceEditorActionOptions) {
  const openRole = () => {
    setEditor(null)
    commands.roleCreation.reset()
    openModal('role')
  }

  const openMemberManagement = () => {
    if (conflictRecoveryActive) return
    setEditor(null)
    mutations.memberUpdate.reset()
    mutations.memberDeactivation.reset()
    openModal('members')
  }

  const openMember = () => {
    setEditor(null)
    commands.memberCreation.reset()
    openModal('member')
  }

  const openMemberEdit = (member: Member) => {
    if (!ensureFreshWorkspace()) return
    mutations.memberUpdate.reset()
    setEditor({ type: 'member', value: member })
    openModal('member')
  }

  const returnToMemberManagement = () => {
    setEditor(null)
    commands.memberCreation.reset()
    mutations.memberUpdate.reset()
    openModal('members')
  }

  const openRoutine = () => {
    if (!roles.length) {
      setView('roles')
      notify('반복 업무를 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditor(null)
    commands.routineCreation.reset()
    openModal('routine')
  }

  const openRound = () => {
    if (!activeRoutines.length) {
      notify('회차를 만들기 전에 반복 업무를 하나 이상 준비해 주세요.', 'error')
      return
    }
    setEditor(null)
    commands.roundCreation.reset()
    openModal('round')
  }

  const openRoleEdit = (role: Role) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, role.id)) {
      setSelectedRoleId(role.id)
      setView('handoff')
      notify('전달한 역할은 수락하거나 취소한 뒤 수정할 수 있어요.', 'error')
      return
    }
    mutations.roleUpdate.reset()
    setEditor({ type: 'role', value: role })
    openModal('role')
  }

  const prepareRoleResource = (source?: RoleResource) => {
    if (!ensureFreshWorkspace()) return
    if (!roles.length) {
      setView('roles')
      notify('자료를 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    if (selectedRole && isRoleHandoffLocked(roleHandoffs, selectedRole.id)) {
      setView('handoff')
      notify('전달한 인수인계는 수락하거나 취소한 뒤 자료를 추가할 수 있어요.', 'error')
      return
    }
    setEditor(source ? { type: 'roleResourceCopy', value: source } : null)
    commands.roleResourceCreation.reset()
    openModal('roleResource')
  }

  const openRoleResource = () => prepareRoleResource()
  const openRoleResourceCopy = (source: RoleResource) => prepareRoleResource(source)

  const openRoleResourceEdit = (resource: RoleResource) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, resource.roleId)) {
      setSelectedRoleId(resource.roleId)
      setView('handoff')
      notify('전달한 인수인계는 수락하거나 취소한 뒤 자료를 수정할 수 있어요.', 'error')
      return
    }
    mutations.roleResourceUpdate.reset()
    setEditor({ type: 'roleResource', value: resource })
    openModal('roleResource')
  }

  const openRoutineEdit = (routine: Routine) => {
    if (!ensureFreshWorkspace()) return
    if (routine.archivedAt) {
      notify('보관한 반복 업무는 복원한 뒤 수정해 주세요.', 'error')
      return
    }
    mutations.routineUpdate.reset()
    setEditor({ type: 'routine', value: routine })
    openModal('routine')
  }

  const openRoundEdit = (round: SeasonRound) => {
    if (!ensureFreshWorkspace()) return
    if (round.archivedAt) {
      notify('보관한 회차는 복원한 뒤 수정해 주세요.', 'error')
      return
    }
    if (busyRoundIds.has(round.id)) return
    mutations.roundUpdate.reset()
    setEditor({ type: 'round', value: round })
    openModal('round')
  }

  const openDecision = () => {
    if (!roles.length || !members.some(isActiveMember)) {
      notify('결정에 연결할 역할과 활동 중인 작성자부터 준비해 주세요.', 'error')
      return
    }
    setEditor(null)
    commands.decisionCreation.reset()
    openModal('decision')
  }

  const openDecisionEdit = (decision: Decision) => {
    if (!ensureFreshWorkspace()) return
    mutations.decisionUpdate.reset()
    setEditor({ type: 'decision', value: decision })
    openModal('decision')
  }

  const openHandoffItem = () => {
    if (!roles.length) {
      setView('roles')
      notify('인수인계를 남길 역할부터 만들어 주세요.', 'error')
      return
    }
    if (selectedRole && isRoleHandoffLocked(roleHandoffs, selectedRole.id)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 항목을 추가할 수 있어요.', 'error')
      return
    }
    setEditor(null)
    commands.handoffItemCreation.reset()
    openModal('handoffItem')
  }

  const openHandoffItemEdit = (item: HandoffItem) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 항목을 수정할 수 있어요.', 'error')
      return
    }
    if (busyHandoffItemIds.has(item.id)) return
    mutations.handoffItemUpdate.reset()
    setEditor({ type: 'handoffItem', value: item })
    openModal('handoffItem')
  }

  return {
    openDecision,
    openDecisionEdit,
    openHandoffItem,
    openHandoffItemEdit,
    openMember,
    openMemberEdit,
    openMemberManagement,
    openRole,
    openRoleEdit,
    openRoleResource,
    openRoleResourceCopy,
    openRoleResourceEdit,
    openRound,
    openRoundEdit,
    openRoutine,
    openRoutineEdit,
    returnToMemberManagement,
  }
}
