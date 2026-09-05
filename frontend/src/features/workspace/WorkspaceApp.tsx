import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useWorkspaceConflictDraft, WorkspaceConflictDraft } from './WorkspaceConflictDraft'
import AccountMembershipPanel from '@/features/membership/AccountMembershipPanel'
import { TeamAccessPanel } from '@/features/team-access/TeamAccessPanel'
import { PersonalWorkPanel } from './PersonalWorkPanel'
import { CalendarSubscriptionPanel, CalendarSubscriptionCleanup } from '@/features/calendar/CalendarSubscriptionPanel'
import { BriefEditionPanel } from '@/features/brief/BriefEditionPanel'
import {
  initialRecordSearchFilters,
  RecordSearchView,
} from './records/RecordSearchView'
import type {
  RecordSearchFilters,
  RecordSearchResult,
} from './records/recordSearch'
import type { WorkspaceScope } from './api'
import {
  useDecisionArchiveMutation,
  useHandoffCompletionMutation,
  useHandoffItemArchiveMutation,
  useRoutineArchiveMutation,
  useRoleResourceArchiveMutation,
  useRoutineExecutionCompletionMutation,
  useSeasonRoundArchiveMutation,
  useUpdateDecisionMutation,
  useUpdateHandoffItemMutation,
  useUpdateMemberDeactivationMutation,
  useUpdateMemberMutation,
  useUpdateRoleMutation,
  useUpdateRoleResourceMutation,
  useUpdateRoutineMutation,
  useUpdateSeasonRoundMutation,
  useWorkspaceQuery,
} from './queries'
import {
  NextSeasonModal,
  SeasonEditModal,
  SeasonEndedBanner,
  SeasonSuccessorCleanupBanner,
  SeasonSwitcherModal,
} from './SeasonLifecycleModals'
import {
  useCreateDecisionCommand,
  useCreateHandoffItemCommand,
  useCreateMemberCommand,
  useCreateRoleCommand,
  useCreateRoleResourceCommand,
  useCreateRoutineCommand,
  useCreateSeasonRoundCommand,
  usePendingContentCreationCleanupCommand,
} from './useContentCreationCommand'
import {
  isWorkspaceAccessDenied,
  useWorkspaceAccessKeyFlow,
} from './useWorkspaceAccessKeyFlow'
import { useWorkspaceConflictRecovery } from './useWorkspaceConflictRecovery'
import { useWorkspaceMutationRecovery } from './useWorkspaceMutationRecovery'
import {
  DecisionModal,
  HandoffItemModal,
  MemberModal,
  MemberManagementModal,
  RoleModal,
  RoleResourceModal,
  RoutineModal,
  RoundScheduleModal,
  SeasonRoundModal,
} from './WorkspaceModals'
import {
  HandoffPreview,
  RoleHandoffModal,
} from './WorkspaceRoleHandoffModals'
import {
  useWorkspaceRoleHandoffFlow,
  type RoleHandoffModalMode,
} from './useWorkspaceRoleHandoffFlow'
import { useWorkspaceSeasonLifecycleFlow } from './useWorkspaceSeasonLifecycleFlow'
import {
  canReceiveWorkspaceFocus,
  focusWorkspaceElement,
  useWorkspaceInspectorSession,
  useWorkspaceModalSession,
  useWorkspaceRecordBusyIds,
  useWorkspaceToast,
} from './useWorkspaceUiState'
import { AccessKeyModal, ShareLinkFallback } from './WorkspaceAccessModals'
import {
  hasWorkspaceAccessKeyRecovery,
  WorkspaceAccessKeyRecovery,
} from './WorkspaceAccessKeyRecovery'
import {
  ContentCreationCleanupBanner,
  MobileNav,
  MobileTopbar,
  Sidebar,
  WorkspaceState,
  WorkspaceSyncStatus,
} from './WorkspaceShell'
import { TodayView } from './WorkspaceTodayView'
import { RoleInspector } from './WorkspaceRoleInspector'
import { HandoffView } from './WorkspaceHandoffView'
import { MemoryView } from './WorkspaceMemoryView'
import { RhythmView } from './WorkspaceRhythmView'
import { RolesView } from './WorkspaceRolesView'
import { formatPilotToday, pilotCalendarDate } from './seasonCalendar'
import { latestRoleHandoff, mutationError } from './workspacePresentation'
import {
  createWorkspaceEditorActions,
  type WorkspaceEditor,
} from './workspaceEditorActions'
import { createWorkspaceContentActions } from './workspaceContentActions'
import type {
  ContinuitySignal,
  Role,
  RoleHandoff,
  SeasonRound,
  ViewKey,
  WorkspaceProjection,
} from './types'

type RoundSelection = {
  roundId: string
  source: 'relevant-default' | 'user'
}
type WorkspaceAppProps = WorkspaceScope & {
  accessDeniedAction?: ReactNode
  onWorkspaceLoaded?: (workspace: WorkspaceProjection) => void
  onSelectSeason: (seasonId: string, accessKey: string) => void
  onSeasonCreated: (seasonId: string, accessKey: string) => void
}

function compareSeasonRounds(left: SeasonRound, right: SeasonRound) {
  const dateOrder = (left.meetingDate ?? '').localeCompare(right.meetingDate ?? '')
  if (dateOrder !== 0) return dateOrder
  const nameOrder = left.name.localeCompare(right.name, 'ko')
  return nameOrder !== 0 ? nameOrder : left.id.localeCompare(right.id)
}

function sortedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort(compareSeasonRounds)
}

function sortedArchivedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort((left, right) => {
    const archiveOrder = (right.archivedAt ?? '').localeCompare(left.archivedAt ?? '')
    return archiveOrder !== 0 ? archiveOrder : compareSeasonRounds(left, right)
  })
}

function relevantSeasonRound(rounds: SeasonRound[]) {
  const priority = {
    OVERDUE: 0,
    IN_PROGRESS: 1,
    PLANNED: 2,
    COMPLETED: 3,
  } as const
  return [...rounds].sort((left, right) => {
    const statusOrder = priority[left.timingStatus] - priority[right.timingStatus]
    if (statusOrder !== 0) return statusOrder
    if (left.timingStatus === 'COMPLETED') return compareSeasonRounds(right, left)
    return compareSeasonRounds(left, right)
  })[0]
}

export default function WorkspaceApp({ teamId, seasonId, accessKey, accessDeniedAction, onWorkspaceLoaded, onSelectSeason, onSeasonCreated }: WorkspaceAppProps) {
  const [currentAccessKey, setCurrentAccessKey] = useState(accessKey)
  const sessionQuery = useAuthSession()
  const scope = { teamId, seasonId, accessKey: currentAccessKey,
    accountId: sessionQuery.data?.authenticated ? sessionQuery.data.accountId : 'anonymous' }
  const workspaceQuery = useWorkspaceQuery(scope)
  const conflictDraftFlow = useWorkspaceConflictDraft(JSON.stringify([
    teamId, seasonId, currentAccessKey,
    sessionQuery.data?.authenticated ? sessionQuery.data.accountId : 'anonymous',
    workspaceQuery.error instanceof ApiError && workspaceQuery.error.code === 'WORKSPACE_ACCESS_DENIED',
  ]))
  const preserveConflictDraft = conflictDraftFlow.preserve
  const contentCreationCleanupCommand = usePendingContentCreationCleanupCommand()
  const memberCreationCommand = useCreateMemberCommand(scope)
  const updateMemberMutation = useUpdateMemberMutation(scope)
  const updateMemberDeactivationMutation = useUpdateMemberDeactivationMutation(scope)
  const roleCreationCommand = useCreateRoleCommand(scope)
  const updateRoleMutation = useUpdateRoleMutation(scope)
  const roleResourceCreationCommand = useCreateRoleResourceCommand(scope)
  const updateRoleResourceMutation = useUpdateRoleResourceMutation(scope)
  const roleResourceArchiveMutation = useRoleResourceArchiveMutation(scope)
  const routineCreationCommand = useCreateRoutineCommand(scope)
  const updateRoutineMutation = useUpdateRoutineMutation(scope)
  const routineArchiveMutation = useRoutineArchiveMutation(scope)
  const roundCreationCommand = useCreateSeasonRoundCommand(scope)
  const updateSeasonRoundMutation = useUpdateSeasonRoundMutation(scope)
  const seasonRoundArchiveMutation = useSeasonRoundArchiveMutation(scope)
  const routineExecutionCompletionMutation = useRoutineExecutionCompletionMutation(scope)
  const decisionCreationCommand = useCreateDecisionCommand(scope)
  const updateDecisionMutation = useUpdateDecisionMutation(scope)
  const decisionArchiveMutation = useDecisionArchiveMutation(scope)
  const handoffItemCreationCommand = useCreateHandoffItemCommand(scope)
  const updateHandoffItemMutation = useUpdateHandoffItemMutation(scope)
  const handoffCompletionMutation = useHandoffCompletionMutation(scope)
  const handoffItemArchiveMutation = useHandoffItemArchiveMutation(scope)

  const [view, setView] = useState<ViewKey>('today')
  const [recordSearchFilters, setRecordSearchFilters] = useState<RecordSearchFilters>(
    initialRecordSearchFilters,
  )
  const [selectedRoleId, setSelectedRoleId] = useState('')
  const [roundSelection, setRoundSelection] = useState<RoundSelection>({
    roundId: '',
    source: 'relevant-default',
  })
  const selectedRoundId = roundSelection.roundId

  useEffect(() => {
    setRecordSearchFilters(initialRecordSearchFilters)
  }, [seasonId])

  const selectRound = (roundId: string) => {
    setRoundSelection({ roundId, source: 'user' })
  }
  const { modal, openModal, closeModal } = useWorkspaceModalSession()
  const [editor, setEditor] = useState<WorkspaceEditor>(null)
  const editingMember = editor?.type === 'member' ? editor.value : null
  const editingRole = editor?.type === 'role' ? editor.value : null
  const editingRoleResource = editor?.type === 'roleResource' ? editor.value : null
  const editingRoutine = editor?.type === 'routine' ? editor.value : null
  const editingRound = editor?.type === 'round' ? editor.value : null
  const editingDecision = editor?.type === 'decision' ? editor.value : null
  const editingHandoffItem = editor?.type === 'handoffItem' ? editor.value : null
  const {
    inspectorOpen,
    inspectorOverlay,
    dismissInspector,
    openInspector,
  } = useWorkspaceInspectorSession()
  const { toast, showToast } = useWorkspaceToast()
  const seasonLifecycleFlow = useWorkspaceSeasonLifecycleFlow({
    scope,
    workspace: workspaceQuery.data,
    currentAccessKey,
    onOpenModal: openModal,
    onCloseModal: closeModal,
    onSelectSeason,
    onSeasonCreated,
    notify: showToast,
    preserveConflictDraft,
  })
  const retryContentCreationCleanup = () => {
    const retry = contentCreationCleanupCommand.retryCleanup()
    if (!retry) return
    void retry.then((completed) => {
      if (completed) showToast('이전 콘텐츠 생성의 완료 기록을 정리했어요.')
    })
  }
  const roleHandoffFlow = useWorkspaceRoleHandoffFlow({
    scope,
    roles: workspaceQuery.data?.roles ?? [],
    roleHandoffs: workspaceQuery.data?.roleHandoffs ?? [],
    modalOpen: modal === 'roleHandoff',
    onOpenModal: () => openModal('roleHandoff'),
    onCloseModal: closeModal,
    onSelectRole: setSelectedRoleId,
    onOpenHandoffView: () => setView('handoff'),
    notify: showToast,
  })
  const {
    copyShareLink,
    pendingRotationIdempotencyKey,
    recoverPendingAccessKeyRotation,
    retryRotationJournalCleanup,
    rotateWorkspaceAccessKey,
    rotationCleanupRetryAvailable,
    rotationError,
    rotationPending,
    rotationStorageError,
    shareUrl,
  } = useWorkspaceAccessKeyFlow({
    scope,
    currentAccessKey,
    accountAccessEnabled: workspaceQuery.data?.team.accountAccessEnabled,
    onAccessKeyChange: setCurrentAccessKey,
    onCloseModal: closeModal,
    onOpenShareLink: () => openModal('shareLink'),
    notify: showToast,
  })
  const {
    busyIds: busyRoutineIds,
    begin: beginRoutineOperation,
    end: endRoutineOperation,
  } = useWorkspaceRecordBusyIds()
  const routineArchiveFocusRef = useRef<{ routineId: string; archived: boolean } | null>(null)

  useEffect(() => {
    const focusRequest = routineArchiveFocusRef.current
    if (!focusRequest || busyRoutineIds.has(focusRequest.routineId)) return

    const target = focusRequest.archived
      ? document.querySelector<HTMLElement>('.routine-archive-shelf > summary')
      : [...document.querySelectorAll<HTMLElement>('.routine-row')]
          .find((row) => row.dataset.routineId === focusRequest.routineId)
          ?.querySelector<HTMLElement>('.routine-archive-button') ?? null
    if (!canReceiveWorkspaceFocus(target)) return

    focusWorkspaceElement(target)
    if (document.activeElement === target) routineArchiveFocusRef.current = null
  }, [busyRoutineIds, workspaceQuery.data?.routines])

  const {
    busyIds: busyRoundIds,
    begin: beginRoundOperation,
    end: endRoundOperation,
  } = useWorkspaceRecordBusyIds()
  const {
    busyIds: busyHandoffItemIds,
    begin: beginHandoffItemOperation,
    end: endHandoffItemOperation,
  } = useWorkspaceRecordBusyIds()
  const discardWorkspaceEditors = () => {
    setEditor(null)
    roleHandoffFlow.discard()
    closeModal()
  }
  const {
    recoveryStatus: conflictRecoveryStatus,
    beginRecovery: beginContentConflictRecovery,
    retryRecovery: retryContentConflictRecovery,
    ensureFreshWorkspace,
  } = useWorkspaceConflictRecovery({
    scopeKey: JSON.stringify([teamId, seasonId, currentAccessKey]),
    refetchWorkspace: () => workspaceQuery.refetch({ throwOnError: true }),
    discardEditors: discardWorkspaceEditors,
    notify: showToast,
  })
  useWorkspaceMutationRecovery({
    teamId,
    seasonId,
    onRoleHandoffConflict: () => beginContentConflictRecovery(
      '다른 구성원이 먼저 바꾼 최신 역할 바통 상태를 불러왔어요.',
    ),
    onWorkspaceContentConflict: () => beginContentConflictRecovery(
      '다른 구성원이 먼저 바꾼 최신 작업 공간을 불러왔어요.',
    ),
    onSeasonEnded: () => {
      discardWorkspaceEditors()
      showToast(
        '다른 구성원이 시즌을 종료했어요. 최신 기록을 읽기 전용으로 다시 불러옵니다.',
        'error',
      )
      void workspaceQuery.refetch()
    },
  })

  useEffect(() => {
    if (workspaceQuery.data) onWorkspaceLoaded?.(workspaceQuery.data)
  }, [onWorkspaceLoaded, workspaceQuery.data])

  useLayoutEffect(() => {
    if (!conflictRecoveryStatus) return

    const activeElement = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    if (activeElement !== document.body && canReceiveWorkspaceFocus(activeElement)) return
    focusWorkspaceElement(null)
  }, [conflictRecoveryStatus])

  useEffect(() => {
    const rounds = sortedSeasonRounds(
      (workspaceQuery.data?.rounds ?? []).filter((round) => !round.archivedAt),
    )
    setRoundSelection((current) => {
      if (current.source === 'user'
        && rounds.some((round) => round.id === current.roundId)) return current

      const relevantRoundId = relevantSeasonRound(rounds)?.id ?? ''
      if (current.source === 'relevant-default'
        && current.roundId === relevantRoundId) return current
      return { roundId: relevantRoundId, source: 'relevant-default' }
    })
  }, [workspaceQuery.data?.rounds])

  const workspaceAccessDenied = isWorkspaceAccessDenied(workspaceQuery.error)

  if (workspaceQuery.isPending) {
    return <WorkspaceState title="작업 공간을 불러오는 중이에요" description="팀의 바통과 이번 시즌 기록을 모으고 있습니다." busy />
  }

  if (!workspaceQuery.data || workspaceAccessDenied) {
    const isAccessDenied = workspaceAccessDenied
    const accessKeyRecovery = isAccessDenied && hasWorkspaceAccessKeyRecovery({
      pendingIdempotencyKey: pendingRotationIdempotencyKey,
      rotationError,
    })
      ? (
          <WorkspaceAccessKeyRecovery
            pendingIdempotencyKey={pendingRotationIdempotencyKey}
            rotationError={rotationError}
            storageError={rotationStorageError}
            pending={rotationPending}
            cleanupRetryAvailable={rotationCleanupRetryAvailable}
            onRecover={recoverPendingAccessKeyRotation}
            onRetryCleanup={retryRotationJournalCleanup}
          />
        )
      : undefined
    return (
      <WorkspaceState
        title="작업 공간을 불러오지 못했어요"
        description={mutationError(workspaceQuery.error)}
        action={<>
          {accessKeyRecovery ?? (isAccessDenied && accessDeniedAction
            ? accessDeniedAction
            : <button type="button" className="primary-button" onClick={() => workspaceQuery.refetch()}>다시 시도하기</button>)}
          {isAccessDenied && <CalendarSubscriptionCleanup teamId={teamId} seasonId={seasonId} />}
        </>}
      />
    )
  }

  const workspace = workspaceQuery.data
  const calendarNow = new Date()
  const calendarDate = pilotCalendarDate(calendarNow, workspace.season.timeZone)
  const calendarLabel = formatPilotToday(calendarNow, workspace.season.timeZone)
  const {
    roles,
    resources,
    routines,
    rounds,
    decisions,
    handoffItems,
    roleHandoffs,
    members,
  } = workspace
  const seasons = workspace.seasons
  const seasonEnded = Boolean(workspace.season.endedAt)
  const accountAccessEnabled = workspace?.team.accountAccessEnabled ?? false
  const canAdminister = !accountAccessEnabled || workspace?.team.permission === 'ADMIN'
  const contentChangesDisabled = seasonEnded || Boolean(conflictRecoveryStatus)
    || (accountAccessEnabled && workspace?.team.permission === 'VIEWER')
  const activeRoutines = routines.filter((routine) => !routine.archivedAt)
  const archivedRoutines = [...routines]
    .filter((routine) => routine.archivedAt)
    .sort((left, right) => (right.archivedAt ?? '').localeCompare(left.archivedAt ?? ''))
  const activeRounds = rounds.filter((round) => !round.archivedAt)
  const archivedRounds = rounds.filter((round) => round.archivedAt)
  const activeDecisions = decisions.filter((decision) => !decision.archivedAt)
  const archivedDecisions = decisions.filter((decision) => decision.archivedAt)
  const activeHandoffItems = handoffItems.filter((item) => !item.archivedAt)
  const archivedHandoffItems = handoffItems.filter((item) => item.archivedAt)
  const activeWorkspace = {
    ...workspace,
    routines: activeRoutines,
    rounds: activeRounds,
    decisions: activeDecisions,
    handoffItems: activeHandoffItems,
  }
  const orderedActiveRounds = sortedSeasonRounds(activeRounds)
  const orderedArchivedRounds = sortedArchivedSeasonRounds(archivedRounds)
  const selectedRound = orderedActiveRounds.find((round) => round.id === selectedRoundId)
    ?? relevantSeasonRound(orderedActiveRounds)
  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  const currentEditingRole = editingRole
    ? roles.find((role) => role.id === editingRole.id) ?? editingRole
    : undefined
  const editingRoleAssignmentLocked = currentEditingRole
    ? latestRoleHandoff(roleHandoffs, currentEditingRole.id)?.status === 'PREPARING'
    : false
  const effectiveSelectedRoleId = selectedRole?.id ?? ''
  const selectedRoleHandoff = selectedRole
    ? latestRoleHandoff(roleHandoffs, selectedRole.id)
    : undefined
  const selectedRoleLocked = selectedRoleHandoff?.status === 'TRANSFERRED'
  const lockedRoleIds = new Set(
    roleHandoffs
      .filter((handoff) => handoff.status === 'TRANSFERRED')
      .map((handoff) => handoff.roleId),
  )
  const pendingCount = selectedRound?.routineExecutions.filter((execution) => execution.status !== 'DONE').length ?? 0
  const completedCount = selectedRound?.routineExecutions.filter((execution) => execution.status === 'DONE').length ?? 0
  const hasPendingRoleCreation = modal === 'role'
    && !editingRole
    && roleCreationCommand.hasPending()
  const hasPendingMemberCreation = modal === 'member'
    && !editingMember
    && memberCreationCommand.hasPending()
  const pendingMemberDeactivationId = updateMemberDeactivationMutation.isPending
    ? updateMemberDeactivationMutation.variables?.id ?? null
    : null
  const hasPendingRoutineCreation = modal === 'routine'
    && !editingRoutine
    && routineCreationCommand.hasPending()
  const hasPendingRoundCreation = modal === 'round'
    && !editingRound
    && roundCreationCommand.hasPending()
  const hasPendingDecisionCreation = modal === 'decision'
    && !editingDecision
    && decisionCreationCommand.hasPending()
  const hasPendingHandoffCreation = modal === 'handoffItem'
    && !editingHandoffItem
    && handoffItemCreationCommand.hasPending()
  const hasPendingRoleResourceCreation = modal === 'roleResource'
    && !editingRoleResource
    && roleResourceCreationCommand.hasPending()
  const selectRole = (
    roleId: string,
    options: { showInspector?: boolean; opener?: HTMLElement } = {},
  ) => {
    const { showInspector = true, opener } = options
    setSelectedRoleId(roleId)
    if (!showInspector) {
      dismissInspector(false)
      return
    }

    openInspector(opener)
  }

  const openView = (key: ViewKey) => {
    setView(key)
    dismissInspector(false)
  }

  const openRecordSearchResult = (result: RecordSearchResult) => {
    if (result.kind === 'decision') {
      openView('memory')
      window.requestAnimationFrame(() => {
        const target = document.querySelector<HTMLElement>(
          `[data-decision-id="${result.id}"]`,
        )
        target?.scrollIntoView({ block: 'center' })
        focusWorkspaceElement(target)
      })
      return
    }
    if (result.kind === 'handoff') {
      setSelectedRoleId(result.roleId)
      openView('handoff')
      window.requestAnimationFrame(() => {
        const target = document.querySelector<HTMLElement>(
          `[data-handoff-item-id="${result.id}"]`,
        )
        target?.scrollIntoView({ block: 'center' })
        focusWorkspaceElement(target)
      })
      return
    }

    setView('roles')
    selectRole(result.roleId)
  }

  const openContinuitySignal = (signal: ContinuitySignal) => {
    if (signal.type === 'ROUTINE_REPEATEDLY_OVERDUE') {
      setSelectedRoleId(signal.roleId)
      openView('rhythm')
    } else if (signal.type === 'HANDOFF_INCOMPLETE') {
      setSelectedRoleId(signal.roleId)
      openView('handoff')
    } else {
      setView('roles')
      selectRole(signal.roleId)
    }

    window.requestAnimationFrame(() => {
      let target: HTMLElement | null = null
      if (signal.type === 'ROUTINE_REPEATEDLY_OVERDUE' && signal.routineId) {
        const routineRow = [...document.querySelectorAll<HTMLElement>('.routine-row')]
          .find((row) => row.dataset.routineId === signal.routineId)
        target = routineRow?.querySelector<HTMLElement>('.routine-copy') ?? null
      } else if (signal.type === 'HANDOFF_INCOMPLETE') {
        target = document.querySelector<HTMLElement>(
          '.handoff-role-tabs [role="tab"][aria-selected="true"]',
        )
      } else {
        target = document.querySelector<HTMLElement>('.role-row.selected .role-row-open')
      }
      focusWorkspaceElement(target)
    })
  }

  const handoffProgress = (roleId: string) => {
    let itemCount = 0
    let completedItemCount = 0
    for (const item of activeHandoffItems) {
      if (item.roleId !== roleId) continue
      itemCount += 1
      if (item.completed) completedItemCount += 1
    }
    if (!itemCount) return 0
    return Math.round((completedItemCount / itemCount) * 100)
  }

  const editorActions = createWorkspaceEditorActions({
    roles,
    members,
    activeRoutines,
    roleHandoffs,
    selectedRole,
    busyRoundIds,
    busyHandoffItemIds,
    conflictRecoveryActive: Boolean(conflictRecoveryStatus),
    ensureFreshWorkspace,
    setEditor,
    setView,
    setSelectedRoleId,
    openModal,
    notify: showToast,
    commands: {
      memberCreation: memberCreationCommand,
      roleCreation: roleCreationCommand,
      roleResourceCreation: roleResourceCreationCommand,
      routineCreation: routineCreationCommand,
      roundCreation: roundCreationCommand,
      decisionCreation: decisionCreationCommand,
      handoffItemCreation: handoffItemCreationCommand,
    },
    mutations: {
      memberUpdate: updateMemberMutation,
      memberDeactivation: updateMemberDeactivationMutation,
      roleUpdate: updateRoleMutation,
      roleResourceUpdate: updateRoleResourceMutation,
      routineUpdate: updateRoutineMutation,
      roundUpdate: updateSeasonRoundMutation,
      decisionUpdate: updateDecisionMutation,
      handoffItemUpdate: updateHandoffItemMutation,
    },
  })
  const {
    openDecision: openDecisionModal,
    openDecisionEdit: openDecisionEditModal,
    openHandoffItem: openHandoffItemModal,
    openHandoffItemEdit: openHandoffItemEditModal,
    openMember: openMemberModal,
    openMemberEdit: openMemberEditModal,
    openMemberManagement: openMemberManagementModal,
    openRole: openRoleModal,
    openRoleEdit: openRoleEditModal,
    openRoleResource: openRoleResourceModal,
    openRoleResourceCopy,
    openRoleResourceEdit: openRoleResourceEditModal,
    openRound: openRoundModal,
    openRoundEdit: openRoundEditModal,
    openRoutine: openRoutineModal,
    openRoutineEdit: openRoutineEditModal,
    returnToMemberManagement,
  } = editorActions

  const openRoleHandoffModal = (
    mode: RoleHandoffModalMode,
    role: Role,
    handoff?: RoleHandoff,
  ) => {
    if (!ensureFreshWorkspace() || contentChangesDisabled) return
    roleHandoffFlow.open(mode, role, handoff)
  }

  const contentActions = createWorkspaceContentActions({
    roles,
    members,
    roleHandoffs,
    activeHandoffItems,
    selectedRound,
    editingMember,
    editingRole,
    editingRoleResource,
    editingRoutine,
    editingRound,
    editingDecision,
    editingHandoffItem,
    ensureFreshWorkspace,
    preserveConflictDraft,
    commands: {
      memberCreation: memberCreationCommand,
      roleCreation: roleCreationCommand,
      roleResourceCreation: roleResourceCreationCommand,
      routineCreation: routineCreationCommand,
      roundCreation: roundCreationCommand,
      decisionCreation: decisionCreationCommand,
      handoffItemCreation: handoffItemCreationCommand,
    },
    mutations: {
      memberUpdate: updateMemberMutation,
      memberDeactivation: updateMemberDeactivationMutation,
      roleUpdate: updateRoleMutation,
      roleResourceUpdate: updateRoleResourceMutation,
      roleResourceArchive: roleResourceArchiveMutation,
      routineUpdate: updateRoutineMutation,
      routineArchive: routineArchiveMutation,
      roundUpdate: updateSeasonRoundMutation,
      roundArchive: seasonRoundArchiveMutation,
      routineExecutionCompletion: routineExecutionCompletionMutation,
      decisionUpdate: updateDecisionMutation,
      decisionArchive: decisionArchiveMutation,
      handoffItemUpdate: updateHandoffItemMutation,
      handoffCompletion: handoffCompletionMutation,
      handoffItemArchive: handoffItemArchiveMutation,
    },
    setEditor,
    setView,
    setSelectedRoleId,
    closeModal,
    openMemberManagement: () => openModal('members'),
    selectRound,
    clearSelectedRound: (roundId) => {
      setRoundSelection((current) => current.roundId === roundId
        ? { roundId: '', source: 'relevant-default' }
        : current)
    },
    focusRoutineArchiveResult: (routineId, archived) => {
      routineArchiveFocusRef.current = { routineId, archived }
    },
    beginRoutineOperation,
    endRoutineOperation,
    beginRoundOperation,
    endRoundOperation,
    beginHandoffItemOperation,
    endHandoffItemOperation,
    notify: showToast,
  })
  const {
    addDecision,
    addHandoffItem,
    addMember,
    addRole,
    addRoleResource,
    addRoutine,
    addSeasonRound,
    toggleHandoff,
    toggleMemberDeactivation,
    toggleRoutineExecution,
    updateDecisionArchive,
    updateExistingDecision,
    updateExistingHandoffItem,
    updateExistingMember,
    updateExistingRole,
    updateExistingRoleResource,
    updateExistingRoutine,
    updateExistingSeasonRound,
    updateHandoffItemArchive,
    updateRoleResourceArchive,
    updateRoutineArchive,
    updateSeasonRoundArchive,
  } = contentActions
  const workspaceInactive = inspectorOverlay && inspectorOpen

  return (
    <>
      <div
        className={`app-shell ${selectedRole ? '' : 'no-inspector'}`}
        inert={workspaceInactive}
        aria-hidden={workspaceInactive || undefined}
      >
        <Sidebar workspace={activeWorkspace} calendarDate={calendarDate} view={view} onNavigate={openView} onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher} onShare={copyShareLink} onManageAccess={() => accountAccessEnabled ? openMemberManagementModal() : openModal('accessKey')} />

        <main className="main-surface" tabIndex={-1}>
          <MobileTopbar accountAccessEnabled={accountAccessEnabled} teamName={workspace.team.name} seasonName={workspace.season.name} onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher} onShare={copyShareLink} onManageAccess={() => accountAccessEnabled ? openMemberManagementModal() : openModal('accessKey')} />
        <div className="page-stage" key={view}>
          <WorkspaceSyncStatus
            updatedAt={workspaceQuery.dataUpdatedAt}
            syncing={workspaceQuery.isFetching}
            failed={workspaceQuery.isRefetchError}
            conflictRecoveryStatus={conflictRecoveryStatus}
            onRefresh={() => {
              if (conflictRecoveryStatus) {
                retryContentConflictRecovery()
                return
              }
              void workspaceQuery.refetch()
            }}
          />
          {conflictDraftFlow.draft && (
            <WorkspaceConflictDraft
              key={conflictDraftFlow.draft.text}
              draft={conflictDraftFlow.draft}
              onDiscard={conflictDraftFlow.discard}
            />
          )}
          {contentCreationCleanupCommand.cleanupRequired && (
            <ContentCreationCleanupBanner
              message={contentCreationCleanupCommand.message}
              pending={contentCreationCleanupCommand.pending}
              onRetry={retryContentCreationCleanup}
            />
          )}
          {seasonLifecycleFlow.cleanup.visible && (
            <SeasonSuccessorCleanupBanner
              pending={seasonLifecycleFlow.cleanup.pending}
              onRetry={seasonLifecycleFlow.actions.retrySuccessorCleanup}
            />
          )}
          {accountAccessEnabled && <p className="workspace-permission-note">계정 권한: {workspace.team.permission === 'ADMIN' ? '관리자' : workspace.team.permission === 'MEMBER' ? '구성원' : '열람자 · 기록 조회만 가능'}</p>}
          <SeasonEndedBanner
            season={workspace.season}
            onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher}
            onCreateNext={seasonLifecycleFlow.actions.openSuccessor}
          />
          {view === 'today' && (
            <TodayView
              workspace={activeWorkspace}
              personalWork={<PersonalWorkPanel
                workspace={workspace}
                accessKey={currentAccessKey}
                onManageMembership={openMemberManagementModal}
                onOpenRound={(roundId, executionId) => {
                  selectRound(roundId)
                  openView('rhythm')
                  window.requestAnimationFrame(() => {
                    const target = document.querySelector<HTMLElement>(
                      `[data-execution-id="${executionId}"] .routine-copy`,
                    )
                    target?.scrollIntoView({ block: 'center' })
                    focusWorkspaceElement(target)
                  })
                }}
                onOpenHandoff={(roleId) => {
                  setSelectedRoleId(roleId)
                  openView('handoff')
                  window.requestAnimationFrame(() => {
                    const target = document.querySelector<HTMLElement>('.handoff-workspace')
                    target?.scrollIntoView({ block: 'center' })
                    focusWorkspaceElement(target)
                  })
                }}
              />}
              weeklyBrief={<><BriefEditionPanel
                workspace={workspace}
                accessKey={currentAccessKey}
                changesDisabled={contentChangesDisabled}
                onManageMembership={openMemberManagementModal}
              /><CalendarSubscriptionPanel
                workspace={workspace}
                accessKey={currentAccessKey}
                changesDisabled={Boolean(conflictRecoveryStatus)}
                onManageMembership={openMemberManagementModal}
              /></>}
              calendarLabel={calendarLabel}
              rounds={orderedActiveRounds}
              archivedRoundCount={orderedArchivedRounds.length}
              selectedRound={selectedRound}
              pendingCount={pendingCount}
              completedCount={completedCount}
              onSelectRound={selectRound}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onOpenDecision={openDecisionModal}
              onToggleRoutine={toggleRoutineExecution}
              onNavigate={openView}
              onOpenContinuitySignal={openContinuitySignal}
              onAddRole={openRoleModal}
              onAddRoutine={openRoutineModal}
              onEditRoutine={openRoutineEditModal}
              selectedRoundBusy={
                contentChangesDisabled
                || Boolean(selectedRound && busyRoundIds.has(selectedRound.id))
              }
              selectedRoundOperationPending={Boolean(
                selectedRound && busyRoundIds.has(selectedRound.id),
              )}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'roles' && (
            <RolesView
              roles={roles}
              roleHandoffs={roleHandoffs}
              members={members}
              selectedRoleId={effectiveSelectedRoleId}
              onSelectRole={selectRole}
              onManageMembers={openMemberManagementModal}
              onAddRole={openRoleModal}
              onEditRole={openRoleEditModal}
              handoffProgress={handoffProgress}
              changesDisabled={contentChangesDisabled}
              memberManagementDisabled={Boolean(conflictRecoveryStatus)}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              season={workspace.season}
              roles={roles}
              routines={activeRoutines}
              archivedRoutines={archivedRoutines}
              rounds={orderedActiveRounds}
              archivedRounds={orderedArchivedRounds}
              selectedRound={selectedRound}
              members={members}
              onSelectRound={selectRound}
              onAddRound={openRoundModal}
              onEditRound={openRoundEditModal}
              onUpdateRoundArchive={updateSeasonRoundArchive}
              onSelectRole={selectRole}
              onToggleRoutine={toggleRoutineExecution}
              onAddRoutine={openRoutineModal}
              onAddRole={openRoleModal}
              onEditRoutine={openRoutineEditModal}
              onUpdateRoutineArchive={updateRoutineArchive}
              onConfigureRoundSchedule={seasonLifecycleFlow.actions.openRoundSchedule}
              busyRoundIds={busyRoundIds}
              busyRoutineIds={busyRoutineIds}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'memory' && (
            <MemoryView
              decisions={activeDecisions}
              archivedDecisions={archivedDecisions}
              roles={roles}
              members={members}
              onOpenDecision={openDecisionModal}
              onAddRole={openRoleModal}
              onManageMembers={openMemberManagementModal}
              onSelectRole={selectRole}
              onEditDecision={openDecisionEditModal}
              onUpdateArchive={updateDecisionArchive}
              archivePending={decisionArchiveMutation.isPending}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'handoff' && (
            <HandoffView
              roles={roles}
              roleHandoffs={roleHandoffs}
              members={members}
              season={workspace.season}
              calendarDate={calendarDate}
              selectedRoleId={effectiveSelectedRoleId}
              handoffItems={activeHandoffItems}
              archivedItems={archivedHandoffItems}
              onSelectRole={(id) => selectRole(id, { showInspector: false })}
              onToggle={toggleHandoff}
              onEditItem={openHandoffItemEditModal}
              onUpdateArchive={updateHandoffItemArchive}
              progress={handoffProgress}
              onPreview={() => openModal('handoffPreview')}
              onAddItem={openHandoffItemModal}
              onAddRole={openRoleModal}
              onPrepareHandoff={(role) => openRoleHandoffModal('prepare', role)}
              onTransferHandoff={(role, handoff) =>
                openRoleHandoffModal('transfer', role, handoff)}
              onAcceptHandoff={(role, handoff) =>
                openRoleHandoffModal('accept', role, handoff)}
              onCancelHandoff={(role, handoff) =>
                openRoleHandoffModal('cancel', role, handoff)}
              busyItemIds={busyHandoffItemIds}
              handoffTransitionPending={roleHandoffFlow.transitionPending}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'records' && (
            <RecordSearchView
              season={workspace.season}
              roles={roles}
              decisions={decisions}
              handoffItems={handoffItems}
              resources={resources}
              filters={recordSearchFilters}
              onFiltersChange={setRecordSearchFilters}
              onOpenResult={openRecordSearchResult}
            />
          )}
        </div>
        </main>

        {selectedRole && (
          <RoleInspector
            role={selectedRole}
            members={members}
            decisions={activeDecisions}
            routines={activeRoutines}
            resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
            handoff={selectedRoleHandoff}
            progress={handoffProgress(selectedRole.id)}
            open={inspectorOpen}
            overlay={inspectorOverlay}
            blocked={Boolean(modal)}
            onClose={() => dismissInspector(true)}
            onAddResource={openRoleResourceModal}
            onEditResource={openRoleResourceEditModal}
            onUpdateResourceArchive={updateRoleResourceArchive}
            onManageMembership={openMemberManagementModal}
            roundRoomScope={scope}
            previousSeasonId={workspace.season.previousSeasonId ?? null}
            onCopyPreviousResource={openRoleResourceCopy}
            changesDisabled={contentChangesDisabled || selectedRoleLocked}
            onOpenHandoff={() => {
              setView('handoff')
              dismissInspector(false)
              window.requestAnimationFrame(() => {
                focusWorkspaceElement(document.querySelector<HTMLElement>('.main-surface'))
              })
            }}
          />
        )}

        <MobileNav view={view} onNavigate={openView} />
      </div>

      {modal === 'decision' && (
        <DecisionModal
          roles={roles}
          members={members}
          selectedRoleId={effectiveSelectedRoleId}
          decision={editingDecision ?? undefined}
          pending={editingDecision
            ? updateDecisionMutation.isPending
            : decisionCreationCommand.isPending}
          error={editingDecision ? updateDecisionMutation.error : decisionCreationCommand.error}
          storageError={editingDecision ? '' : decisionCreationCommand.storageError}
          recoveryAvailable={editingDecision ? false : hasPendingDecisionCreation}
          onClose={closeModal}
          onSave={editingDecision ? updateExistingDecision : addDecision}
        />
      )}
      {modal === 'members' && (
        <MemberManagementModal
          members={members}
          accountMembershipPanel={(
            <>
            <AccountMembershipPanel
              teamId={teamId}
              seasonId={seasonId}
              accessKey={currentAccessKey}
              members={members}
              changesDisabled={contentChangesDisabled}
              seasonEnded={seasonEnded}
            />
            <TeamAccessPanel scope={scope} />
            </>
          )}
          pendingMemberId={pendingMemberDeactivationId}
          error={updateMemberDeactivationMutation.error}
          changesDisabled={contentChangesDisabled || !canAdminister}
          onAdd={openMemberModal}
          onEdit={openMemberEditModal}
          onToggleDeactivation={toggleMemberDeactivation}
          onClose={closeModal}
        />
      )}
      {modal === 'member' && (
        <MemberModal
          members={members}
          member={editingMember ?? undefined}
          pending={editingMember
            ? updateMemberMutation.isPending
            : memberCreationCommand.isPending}
          error={editingMember ? updateMemberMutation.error : memberCreationCommand.error}
          storageError={editingMember ? '' : memberCreationCommand.storageError}
          recoveryAvailable={editingMember ? false : hasPendingMemberCreation}
          onClose={() => {
            setEditor(null)
            closeModal()
          }}
          onCancel={returnToMemberManagement}
          onSave={editingMember ? updateExistingMember : addMember}
        />
      )}
      {modal === 'role' && (
        <RoleModal
          members={members}
          season={workspace.season}
          role={currentEditingRole}
          assignmentLocked={editingRoleAssignmentLocked}
          pending={editingRole
            ? updateRoleMutation.isPending
            : roleCreationCommand.isPending}
          error={editingRole ? updateRoleMutation.error : roleCreationCommand.error}
          storageError={editingRole ? '' : roleCreationCommand.storageError}
          recoveryAvailable={editingRole ? false : hasPendingRoleCreation}
          onClose={closeModal}
          onSave={editingRole ? updateExistingRole : addRole}
        />
      )}
      {modal === 'routine' && (
        <RoutineModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          routine={editingRoutine ?? undefined}
          pending={editingRoutine
            ? updateRoutineMutation.isPending
            : routineCreationCommand.isPending}
          error={editingRoutine ? updateRoutineMutation.error : routineCreationCommand.error}
          storageError={editingRoutine ? '' : routineCreationCommand.storageError}
          recoveryAvailable={editingRoutine ? false : hasPendingRoutineCreation}
          onClose={closeModal}
          onSave={editingRoutine ? updateExistingRoutine : addRoutine}
        />
      )}
      {modal === 'roundSchedule' && (
        <RoundScheduleModal
          season={workspace.season}
          pending={seasonLifecycleFlow.roundSchedule.pending}
          error={seasonLifecycleFlow.roundSchedule.error}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.saveRoundSchedule}
        />
      )}
      {modal === 'roleResource' && (
        <RoleResourceModal
          roles={roles}
          lockedRoleIds={lockedRoleIds}
          selectedRoleId={effectiveSelectedRoleId}
          resource={editingRoleResource ?? undefined}
          initialResource={editor?.type === 'roleResourceCopy' ? editor.value : undefined}
          pending={editingRoleResource
            ? updateRoleResourceMutation.isPending
            : roleResourceCreationCommand.isPending}
          error={editingRoleResource
            ? updateRoleResourceMutation.error
            : roleResourceCreationCommand.error}
          storageError={editingRoleResource ? '' : roleResourceCreationCommand.storageError}
          recoveryAvailable={editingRoleResource ? false : hasPendingRoleResourceCreation}
          onClose={closeModal}
          onSave={editingRoleResource ? updateExistingRoleResource : addRoleResource}
        />
      )}
      {modal === 'round' && (
        <SeasonRoundModal
          season={workspace.season}
          roundCount={rounds.length}
          round={editingRound ?? undefined}
          pending={editingRound
            ? busyRoundIds.has(editingRound.id)
            : roundCreationCommand.isPending}
          error={editingRound ? updateSeasonRoundMutation.error : roundCreationCommand.error}
          storageError={editingRound ? '' : roundCreationCommand.storageError}
          recoveryAvailable={editingRound ? false : hasPendingRoundCreation}
          onClose={() => {
            setEditor(null)
            closeModal()
          }}
          onSave={editingRound ? updateExistingSeasonRound : addSeasonRound}
        />
      )}
      {modal === 'handoffItem' && (
        <HandoffItemModal
          roles={roles}
          lockedRoleIds={lockedRoleIds}
          selectedRoleId={effectiveSelectedRoleId}
          item={editingHandoffItem ?? undefined}
          pending={editingHandoffItem
            ? busyHandoffItemIds.has(editingHandoffItem.id)
            : handoffItemCreationCommand.isPending}
          error={editingHandoffItem
            ? updateHandoffItemMutation.error
            : handoffItemCreationCommand.error}
          storageError={editingHandoffItem ? '' : handoffItemCreationCommand.storageError}
          recoveryAvailable={editingHandoffItem ? false : hasPendingHandoffCreation}
          onClose={closeModal}
          onSave={editingHandoffItem ? updateExistingHandoffItem : addHandoffItem}
        />
      )}
      {modal === 'roleHandoff' && roleHandoffFlow.action && roleHandoffFlow.actionRole && (
        <RoleHandoffModal
          accountAccessEnabled={accountAccessEnabled}
          key={`${roleHandoffFlow.action.mode}:${roleHandoffFlow.actionRole.id}:${roleHandoffFlow.actionTarget?.id ?? 'new'}`}
          mode={roleHandoffFlow.action.mode}
          role={roleHandoffFlow.actionRole}
          handoff={roleHandoffFlow.actionTarget}
          members={members}
          season={workspace.season}
          items={activeHandoffItems}
          resources={resources.filter((resource) => !resource.archivedAt)}
          pending={roleHandoffFlow.modalPending}
          error={roleHandoffFlow.modalError}
          storageError={roleHandoffFlow.modalStorageError}
          recoveryAvailable={roleHandoffFlow.recoveryAvailable}
          onClose={roleHandoffFlow.close}
          onPrepare={roleHandoffFlow.prepare}
          onTransfer={roleHandoffFlow.transfer}
          onAccept={roleHandoffFlow.accept}
          onCancel={roleHandoffFlow.cancel}
        />
      )}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          workspaceLabel={`${workspace.team.name} · ${workspace.season.name}`}
          members={members}
          routines={activeRoutines.filter((routine) => routine.ownerRoleId === selectedRole.id)}
          decisions={activeDecisions}
          resources={resources.filter(
            (resource) => resource.roleId === selectedRole.id && !resource.archivedAt,
          )}
          items={activeHandoffItems.filter((item) => item.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          onClose={closeModal}
        />
      )}
      {modal === 'shareLink' && <ShareLinkFallback shareUrl={shareUrl} onClose={closeModal} />}
      {modal === 'accessKey' && (
        <AccessKeyModal
          pending={rotationPending}
          error={rotationError}
          storageError={rotationStorageError}
          onClose={closeModal}
          onShare={copyShareLink}
          onRotate={rotateWorkspaceAccessKey}
        />
      )}
      {modal === 'seasonSwitcher' && (
        <SeasonSwitcherModal
          teamName={workspace.team.name}
          currentSeason={workspace.season}
          seasons={seasons}
          calendarDate={calendarDate}
          endingPending={seasonLifecycleFlow.switcher.endingPending}
          endingError={seasonLifecycleFlow.switcher.endingError}
          onClose={closeModal}
          onSelect={seasonLifecycleFlow.actions.selectSeason}
          onEdit={seasonLifecycleFlow.actions.openEdit}
          onToggleEnding={seasonLifecycleFlow.actions.toggleEnding}
          onCreateNext={seasonLifecycleFlow.actions.openSuccessor}
        />
      )}
      {modal === 'seasonEdit' && (
        <SeasonEditModal
          season={workspace.season}
          pending={seasonLifecycleFlow.edit.pending}
          error={seasonLifecycleFlow.edit.error}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.saveSeason}
        />
      )}
      {modal === 'seasonSuccessor' && (
        <NextSeasonModal
          sourceSeason={workspace.season}
          roles={roles}
          routines={activeRoutines}
          cleanupRequired={seasonLifecycleFlow.successor.cleanupRequired}
          pending={seasonLifecycleFlow.successor.pending}
          error={seasonLifecycleFlow.successor.error}
          storageError={seasonLifecycleFlow.successor.storageError}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.createSuccessor}
        />
      )}

      {toast && (
        <div className={`toast ${toast.tone === 'error' ? 'toast-error' : ''}`} role="status">
          <Icon name={toast.tone === 'error' ? 'alert' : 'check'} size={16} />{toast.message}
        </div>
      )}
    </>
  )
}
